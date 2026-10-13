package com.hmdp.voucher.payment.mock;

import com.hmdp.common.exception.BadRequestException;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.mapper.PaymentMapper;
import com.hmdp.voucher.payment.config.PayProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/** mock 渠道的「收银台 + 付款动作」端点（仅在 {@code hmdp.pay.mock.enabled=true} 时存在）。三种端点：{@code GET
 * /cashier/{outTradeNo}} 无副作用；{@code POST /pay/{outTradeNo}} 有副作用 —— 造合法报文并真的投递到
 * {@code /voucher-order/pay/notify/alipay}（或 {@code /wechat}），走 HTTP 故验签、form 解码、Controller、状态机全是真的；{@code GET /payload/{outTradeNo}} 无副作用，只吐出签名合法的报文（供人工 curl 打真实端点，或改成非法值后重打以验证「验签真的在挡」）。{@code /pay} 用 HTTP 自调用而非 new 一个 Controller 调方法，是为了真正经过 Spring MVC 参数绑定（支付宝 form-urlencoded、微信原始 body + 4 个头），这段接线恰恰最容易出错；代价是无法纯单测跑，签名正确性由 {@code MockNotifySignerTest} 用零网络方式钉死。⚠️ 安全：{@code /voucher-order/pay/mock/**} 能让调用者凭一个请求把订单置为已支付，绝不能出现在网关的 {@code excludePaths}（免登录白名单）里。 */
@Tag(name = "mock 支付渠道", description = "验收用：默认关闭，生产 profile 下开启会拒绝启动")
@RestController
@RequestMapping("/voucher-order/pay/mock")
@ConditionalOnProperty(prefix = "hmdp.pay.mock", name = "enabled", havingValue = "true")
@Slf4j
public class MockPayController {

    private static final String PROVIDER_ALIPAY = "alipay";
    private static final String PROVIDER_WECHAT = "wechat";

    /** 自调用超时：这不该慢，慢就说明本服务自己有问题，早失败早暴露 */
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private final PaymentMapper paymentMapper;
    private final PayProperties payProperties;
    private final int serverPort;
    private final RestTemplate restTemplate;

    public MockPayController(PaymentMapper paymentMapper,
                             PayProperties payProperties,
                             @Value("${server.port:8084}") int serverPort) {
        this.paymentMapper = paymentMapper;
        this.payProperties = payProperties;
        this.serverPort = serverPort;
        this.restTemplate = buildRestTemplate();
    }

    // ==================================================================
    // 收银台（无副作用）
    // ==================================================================

    @Operation(summary = "mock 收银台（无副作用）")
    @GetMapping("/cashier/{outTradeNo}")
    public Map<String, Object> cashier(@PathVariable String outTradeNo) {
        String baseUrl = baseUrl();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("mock", true);
        info.put("notice", "这是 mock 收银台，不涉及任何真实资金。"
                + "「支付成功」= 投递一份签名合法的回调到真实的 /voucher-order/pay/notify 端点。");
        info.put("outTradeNo", outTradeNo);
        info.put("paySuccessAlipay", MockPayUrls.pay(baseUrl, outTradeNo, PROVIDER_ALIPAY, true));
        info.put("paySuccessWechat", MockPayUrls.pay(baseUrl, outTradeNo, PROVIDER_WECHAT, true));
        info.put("payFail", MockPayUrls.pay(baseUrl, outTradeNo, PROVIDER_ALIPAY, false));
        info.put("rawPayloadAlipay", MockPayUrls.payload(baseUrl, outTradeNo, PROVIDER_ALIPAY));
        info.put("rawPayloadWechat", MockPayUrls.payload(baseUrl, outTradeNo, PROVIDER_WECHAT));
        return info;
    }

    // ==================================================================
    // 付款动作（有副作用）
    // ==================================================================

    @Operation(summary = "mock 付款：造合法回调并投递到真实通知端点")
    @PostMapping("/pay/{outTradeNo}")
    public Map<String, Object> pay(@PathVariable String outTradeNo,
                                   @RequestParam(defaultValue = PROVIDER_ALIPAY) String provider,
                                   @RequestParam(defaultValue = "true") boolean success) {
        Payment payment = requirePayment(outTradeNo);
        String target = MockPayUrls.notify(baseUrl(), provider);
        ResponseEntity<String> response = PROVIDER_WECHAT.equalsIgnoreCase(provider)
                ? deliverWechat(payment, success, target)
                : deliverAlipay(payment, success, target);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outTradeNo", outTradeNo);
        result.put("provider", provider.toLowerCase());
        result.put("success", success);
        result.put("notifyUrl", target);
        result.put("httpStatus", response.getStatusCodeValue());
        result.put("notifyResponse", response.getBody());

        // 回读一次「回调处理完之后」的真实状态，验收时不必再手动查库
        Payment after = paymentMapper.selectByOutTradeNo(outTradeNo);
        result.put("paymentStatusAfter", after == null ? null : after.getStatus());
        result.put("tradeNoAfter", after == null ? null : after.getTradeNo());
        log.warn("mock 渠道投递回调：outTradeNo={}, provider={}, success={}, httpStatus={}, statusAfter={}",
                outTradeNo, provider, success, response.getStatusCodeValue(),
                after == null ? null : after.getStatus());
        return result;
    }

    // ==================================================================
    // 只吐报文（无副作用）—— 供人工 curl 与「篡改后重放」用例
    // ==================================================================

    @Operation(summary = "吐出一份签名合法的回调报文（不投递）")
    @GetMapping("/payload/{outTradeNo}")
    public Map<String, Object> payload(@PathVariable String outTradeNo,
                                       @RequestParam(defaultValue = PROVIDER_ALIPAY) String provider) {
        Payment payment = requirePayment(outTradeNo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outTradeNo", outTradeNo);
        result.put("success", true);
        result.put("notifyUrl", MockPayUrls.notify(baseUrl(), provider));

        if (PROVIDER_WECHAT.equalsIgnoreCase(provider)) {
            MockNotifySigner.WechatNotify notify = MockNotifySigner.wechatNotify(
                    payProperties, outTradeNo, amountOf(payment), true);
            result.put("contentType", MediaType.APPLICATION_JSON_VALUE);
            result.put("headers", notify.headers());
            result.put("body", notify.body());
            result.put("howTo", "POST 上面的 notifyUrl，带上 headers 与 body（body 必须原样，"
                    + "任何字节改动都会导致验签失败）");
        } else {
            Map<String, String> form = MockNotifySigner.alipayNotify(
                    payProperties, outTradeNo, amountOf(payment), true);
            result.put("contentType", MediaType.APPLICATION_FORM_URLENCODED_VALUE);
            result.put("form", form);
            result.put("howTo", "POST 上面的 notifyUrl，以 form-urlencoded 提交上面的字段");
        }
        return result;
    }

    // ==================================================================
    // 投递实现
    // ==================================================================

    private ResponseEntity<String> deliverAlipay(Payment payment, boolean success, String target) {
        Map<String, String> params = MockNotifySigner.alipayNotify(
                payProperties, payment.getOutTradeNo(), amountOf(payment), success);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        return post(target, new HttpEntity<>(form, headers));
    }

    private ResponseEntity<String> deliverWechat(Payment payment, boolean success, String target) {
        MockNotifySigner.WechatNotify notify = MockNotifySigner.wechatNotify(
                payProperties, payment.getOutTradeNo(), amountOf(payment), success);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        notify.headers().forEach(headers::add);
        return post(target, new HttpEntity<>(notify.body(), headers));
    }

    private ResponseEntity<String> post(String target, HttpEntity<?> entity) {
        try {
            return restTemplate.postForEntity(target, entity, String.class);
        } catch (RestClientException e) {
            // 自调用失败说明本服务自身有问题（端口被占 / 通知端点没注册），
            // 直接抛出而不是吞掉 —— 否则验收会看到「投递成功但状态没变」，更难查
            log.error("mock 回调投递失败：target={}, err={}", target, e.getMessage());
            throw new BadRequestException("mock 回调投递失败：" + e.getMessage());
        }
    }

    private Payment requirePayment(String outTradeNo) {
        Payment payment = paymentMapper.selectByOutTradeNo(outTradeNo);
        if (payment == null) {
            throw new BadRequestException("mock 渠道找不到支付单：" + outTradeNo
                    + "（请先用 POST /voucher-order/pay/{orderId} 发起支付）");
        }
        return payment;
    }

    private static long amountOf(Payment payment) {
        if (payment.getAmount() == null) {
            throw new BadRequestException("支付单金额为空，无法造出合法回调：" + payment.getOutTradeNo());
        }
        return payment.getAmount();
    }

    private String baseUrl() {
        return MockPayUrls.baseUrl(payProperties, serverPort);
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        factory.setReadTimeout(READ_TIMEOUT_MILLIS);
        return new RestTemplate(factory);
    }
}
