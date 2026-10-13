package com.hmdp.voucher.controller;

import com.hmdp.voucher.payment.service.IPaymentNotifyService;
import com.hmdp.voucher.payment.domain.NotifyAck;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 支付渠道异步通知入口（免登录，必须公网可达 https）。本类只做 HTTP 适配：收报文 → 委托
 * {@link IPaymentNotifyService} 完成解析/落地/ack 决策 → 把中性 {@link NotifyAck} 翻译成渠道各自的线格式。
 * 业务逻辑一律不在此处。本路径须在网关 excludePaths 白名单且绝不能进 internalPaths（Nacos 配置）。 */
@Tag(name = "支付回调", description = "支付宝 / 微信支付异步通知入口")
@RestController
@RequestMapping("/voucher-order/pay/notify")
@Slf4j
@RequiredArgsConstructor
public class PaymentNotifyController {

    /** 支付宝约定：响应体为这个字符串即表示「已收到、别再推」。其它任何内容都会触发重推。 */
    private static final String ALIPAY_ACK = "success";

    private static final String ALIPAY_NACK = "failure";

    private final IPaymentNotifyService paymentNotifyService;

    /** 支付宝异步通知：用 {@code @RequestParam Map} 收 form-urlencoded（Spring 负责解码，且可脱容器单测），
     * 不用 {@code HttpServletRequest}。 */
    @Operation(summary = "支付宝异步通知")
    @PostMapping("/alipay")
    public String alipayNotify(@RequestParam Map<String, String> params) {
        NotifyAck ack = paymentNotifyService.handleAlipayNotify(params);
        return ack.isAckSuccess() ? ALIPAY_ACK : ALIPAY_NACK;
    }

    /** 微信支付通知（APIv3）：成功 → HTTP 200；失败 → 非 2xx + {@code {"code":"FAIL"}}（非 2xx 才触发重推）。
     * 用 {@code @RequestHeader} + {@code @RequestBody String} 收原始报文：签名对 timestamp+nonce+原始 body 做，
     * 自动反序列化改动字节就永远验不过签；也因此可脱离容器单测。 */
    @Operation(summary = "微信支付通知")
    @PostMapping("/wechat")
    public ResponseEntity<Map<String, Object>> wechatNotify(
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            @RequestBody(required = false) String body) {

        NotifyAck ack = paymentNotifyService.handleWechatNotify(serial, nonce, timestamp, signature, body);
        if (ack.isRejected()) {
            // 协议层拒绝（报文/验签不过）：对应微信 400 BAD_REQUEST
            return wechatFailure(HttpStatus.BAD_REQUEST, ack.getMessage());
        }
        if (!ack.isAckSuccess()) {
            // 需人工介入的结局一律不 ack：让微信继续重推，异常才不会只烂在日志里
            return wechatFailure(HttpStatus.INTERNAL_SERVER_ERROR, ack.getMessage());
        }
        return wechatSuccess();
    }

    private static ResponseEntity<Map<String, Object>> wechatSuccess() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "SUCCESS");
        body.put("message", "成功");
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> wechatFailure(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "FAIL");
        body.put("message", message == null ? "处理失败" : message);
        return ResponseEntity.status(status).body(body);
    }
}
