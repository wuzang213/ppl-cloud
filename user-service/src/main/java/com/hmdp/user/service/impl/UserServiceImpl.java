package com.hmdp.user.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.common.utils.RegexUtils;
import com.hmdp.common.utils.PasswordEncoder;
import com.hmdp.common.exception.BadRequestException;
import com.hmdp.common.exception.UnauthorizedException;
import com.hmdp.user.config.JwtProperties;
import com.hmdp.user.config.JwtUtils;
import com.hmdp.user.domain.TokenPair;
import com.hmdp.user.constants.UserConstants;
import com.hmdp.common.utils.UserHolder;
import com.hmdp.user.domain.LoginFormDTO;
import com.hmdp.user.domain.UserInfo;
import com.hmdp.user.domain.UserInfoDTO;
import com.hmdp.user.domain.User;
import com.hmdp.user.domain.UserDTO;
import com.hmdp.user.mapper.UserMapper;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.user.service.IUserService;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hmdp.common.constants.RedisConstants.*;
import static com.hmdp.common.constants.SystemConstants.USER_NICK_NAME_PREFIX;


@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private JwtUtils jwtUtils;

    @Resource
    private JwtProperties jwtProperties;

    @Resource
    private Environment environment;

    @Resource
    private OutboxWriter outboxWriter;

    // ===== Lua 脚本（原子校验/删除双向索引，替代 keys()）=====

    /**
     * refreshToken 校验 + 原子删除双向索引。
     * REFRESH_TOKEN_KEY 的 value 格式为 "refreshToken:tokenVersion"。
     * 返回空串表示校验失败，返回 version 字符串表示成功且已删除。
     * KEYS[1]=正向key(login:refresh:{uid}:{deviceId})  KEYS[2]=反向key(login:refresh:index:{refreshToken})
     * ARGV[1]=提交的 refreshToken
     */
    private static final DefaultRedisScript<String> REFRESH_VALIDATE_SCRIPT;

    /**
     * logout 原子删除双向索引。
     * KEYS[1]=反向key  ARGV[1]=REFRESH_TOKEN_KEY 前缀
     * 返回 1 删除成功，0 反向 key 不存在。
     */
    private static final DefaultRedisScript<Long> LOGOUT_SCRIPT;

    /**
     * kickAllDevices 原子删除所有设备的双向索引 + 设备集合。
     * KEYS[1]=设备集合key(login:devices:{userId})
     * ARGV[1]=REFRESH_TOKEN_KEY 前缀  ARGV[2]=REFRESH_INDEX_KEY 前缀  ARGV[3]=userId
     * 返回删除的设备数。
     */
    private static final DefaultRedisScript<Long> KICK_ALL_SCRIPT;

    static {
        REFRESH_VALIDATE_SCRIPT = new DefaultRedisScript<>();
        REFRESH_VALIDATE_SCRIPT.setLocation(new ClassPathResource("refresh_validate.lua"));
        REFRESH_VALIDATE_SCRIPT.setResultType(String.class);

        LOGOUT_SCRIPT = new DefaultRedisScript<>();
        LOGOUT_SCRIPT.setLocation(new ClassPathResource("logout.lua"));
        LOGOUT_SCRIPT.setResultType(Long.class);

        KICK_ALL_SCRIPT = new DefaultRedisScript<>();
        KICK_ALL_SCRIPT.setLocation(new ClassPathResource("kick_all_devices.lua"));
        KICK_ALL_SCRIPT.setResultType(Long.class);
    }


    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            throw new BadRequestException(UserConstants.PHONE_INVALID);
        }
        // 手机号 60 秒冷却防刷
        String coolKey = LOGIN_CODE_COOL_KEY + phone;
        Boolean cooled = stringRedisTemplate.hasKey(coolKey);
        if (Boolean.TRUE.equals(cooled)) {
            throw new BadRequestException(UserConstants.CODE_SEND_TOO_FREQUENT);
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4.保存验证码到 redis    // set key value ex 120
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 设置 60 秒冷却
        stringRedisTemplate.opsForValue().set(coolKey, "1", 60, TimeUnit.SECONDS);
        // 5.发送验证码
        // 仅开发环境打印验证码（未接入真实短信平台），生产环境只记录脱敏手机号
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            log.info("开发环境验证码：{}", code);
        } else {
            log.debug("发送短信验证码成功，手机号：{}", phone.substring(0, 3) + "****" + phone.substring(7));
        }
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            throw new BadRequestException(UserConstants.PHONE_INVALID);
        }
        // 2.5 密码登录
        if (StrUtil.isBlank(loginForm.getCode()) && StrUtil.isNotBlank(loginForm.getPassword())) {
            User user = query().eq("phone", phone).one();
            if (user == null || StrUtil.isBlank(user.getPassword())
                    || !PasswordEncoder.matches(user.getPassword(), loginForm.getPassword())) {
                throw new BadRequestException(UserConstants.PASSWORD_ERROR);
            }
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            return Result.ok(createToken(userDTO));
        }
        // 3.从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 不一致，报错
            throw new BadRequestException(UserConstants.CODE_ERROR);
        }

        // 4.一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }

        // 7.保存用户信息到 redis中
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(createToken(userDTO));
    }

    @Override
    public Result logout(String refreshToken) {
        if (StrUtil.isNotBlank(refreshToken)) {
            // Bug #5：Lua 原子删除双向索引，避免分步删除在异常/并发下残留脏数据
            stringRedisTemplate.execute(LOGOUT_SCRIPT,
                    Collections.singletonList(REFRESH_INDEX_KEY + refreshToken),
                    REFRESH_TOKEN_KEY);
        }
        log.info("logout, refreshToken: {}", refreshToken);
        UserHolder.removeUser();
        return Result.ok();
    }

    @Override
    public Result refreshToken(String refreshToken) {
        if (StrUtil.isBlank(refreshToken)) {
            throw new UnauthorizedException(UserConstants.REFRESH_TOKEN_INVALID);
        }
        String uidDevice = stringRedisTemplate.opsForValue().get(REFRESH_INDEX_KEY + refreshToken);
        if (StrUtil.isBlank(uidDevice)) {
            throw new UnauthorizedException(UserConstants.REFRESH_TOKEN_INVALID);
        }
        String[] parts = uidDevice.split(":");
        if (parts.length != 2) {
            throw new UnauthorizedException(UserConstants.REFRESH_TOKEN_INVALID);
        }
        Long userId = Long.valueOf(parts[0]);
        String deviceId = parts[1];

        // Lua 原子校验+删除双向索引（一次往返，防并发重用旧 refreshToken）
        // 脚本返回签发时的 tokenVersion，与 DB 当前版本比对，防止踢人/改密码后旧 refreshToken 刷出新 token
        String issuedVersion = stringRedisTemplate.execute(REFRESH_VALIDATE_SCRIPT,
                Arrays.asList(REFRESH_TOKEN_KEY + uidDevice, REFRESH_INDEX_KEY + refreshToken),
                refreshToken);
        if (StrUtil.isBlank(issuedVersion)) {
            throw new UnauthorizedException(UserConstants.REFRESH_TOKEN_INVALID);
        }

        // tokenVersion 校验——DB 版本号与签发版本号不一致说明期间执行过踢人/改密码，拒绝刷新
        Integer dbVersion = getTokenVersion(userId);
        if (!dbVersion.toString().equals(issuedVersion)) {
            throw new UnauthorizedException(UserConstants.TOKEN_INVALID);
        }

        User user = getById(userId);
        if (user == null) {
            throw new UnauthorizedException(UserConstants.TOKEN_INVALID);
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(createToken(userDTO, deviceId));
    }

    /**
     *
     * 强制下线所有设备
     * @return
     */
    @Override
    public Result kickAllDevices() {
        Long userId = UserHolder.getUser().getId();
        update().setSql("token_version = token_version + 1").eq("id", userId).update();
        Integer version = getTokenVersion(userId);
        stringRedisTemplate.opsForValue().set(
                TOKEN_VERSION_KEY + userId, version.toString(),
                jwtProperties.getRefreshTtlDays(), TimeUnit.DAYS);
        // Lua 脚本 SMEMBERS + 遍历原子删除双向索引，替代 keys() 全库扫描
        // 精确删除正向+反向索引，无残留脏数据，不阻塞 Redis 单线程
        stringRedisTemplate.execute(KICK_ALL_SCRIPT,
                Collections.singletonList(LOGIN_DEVICES_KEY + userId),
                REFRESH_TOKEN_KEY, REFRESH_INDEX_KEY, userId.toString());
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        // 1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        // 2. 保存用户
        save(user);
        return user;
    }

    @Override
    @Transactional
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();

        // 凌晨5点前算前一天
        LocalDateTime signDate = now.getHour() < 5 ? now.minusDays(1) : now;

        // 3.拼接key
        String keySuffix = signDate.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 5. 【原子操作】setBit 返回该位原来的值
        //    true = 已经签到过，false = 未签到
        Boolean wasSigned = stringRedisTemplate.opsForValue()
                .setBit(key, dayOfMonth - 1, true);

        // 6. 如果已经签到过，返回失败
        if (Boolean.TRUE.equals(wasSigned)) {
            return Result.fail("今日已签到，请明天5:00后再来！");
        }
        // 7. 设置 Key 过期时间（保留35天，覆盖所有签到周期）
        stringRedisTemplate.expire(key, Duration.ofDays(35));
        // 8. 增加积分：写 outbox，由 UserCacheListener 消费时执行积分累加，
        //    有记录可对账重试，不再依赖请求线程内的 afterCommit（失败即丢失）
        Map<String, Object> data = new HashMap<>();
        data.put("signDate", signDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        CacheSyncMessage creditsMsg = new CacheSyncMessage("USER", userId, null, "USER_SIGNED");
        creditsMsg.setData(data);
        outboxWriter.write("USER", userId, "USER_SIGNED", creditsMsg);
        return Result.ok();
    }

    @Override
    @Transactional
    public Result register(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone) || StrUtil.isBlank(loginForm.getPassword())) {
            throw new BadRequestException(UserConstants.PHONE_PASSWORD_INVALID);
        }
        if (query().eq("phone", phone).count() > 0) {
            throw new BadRequestException(UserConstants.PHONE_REGISTERED);
        }
        User user = new User();
        user.setPhone(phone);
        user.setPassword(PasswordEncoder.encode(loginForm.getPassword()));
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return Result.ok();
    }

    @Override
    @Transactional
    public Result updateInfo(UserInfoDTO dto) {
        Long userId = UserHolder.getUser().getId();
        User user = new User();
        user.setId(userId);
        user.setNickName(dto.getNickName());
        user.setIcon(dto.getIcon());
        updateById(user);

        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            info = new UserInfo();
            info.setUserId(userId);
        }
        info.setCity(dto.getCity());
        info.setIntroduce(dto.getIntroduce());
        info.setGender(dto.getGender());
        info.setBirthday(dto.getBirthday());
        userInfoService.saveOrUpdate(info);
        return Result.ok();
    }

    @Override
    public Result getCredits() {
        Long userId = UserHolder.getUser().getId();
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            return Result.ok(0);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("credits", info.getCredits());
        data.put("level", info.getLevel());
        return Result.ok(data);
    }

    // 生成 access + refresh 双 token
    private TokenPair createToken(UserDTO userDTO) {
        return createToken(userDTO, null);
    }

    private TokenPair createToken(UserDTO userDTO, String deviceId) {
        //deviceId为空就随机生成设备id，支持多设备
        if (StrUtil.isBlank(deviceId)) {
            deviceId = UUID.randomUUID().toString(true);
        }
        //读取数据库用户的token_version
        Integer version = getTokenVersion(userDTO.getId());
        // JWT 只携带不可变/安全字段，去掉 nickName/icon（改名后 JWT 无法更新，下游会拿到过期值）
        String accessToken = jwtUtils.createAccessToken(userDTO.getId(), version, deviceId, userDTO.getRole());
        String refreshToken = deviceId + "." + UUID.randomUUID().toString(true);
        String uidDevice = userDTO.getId() + ":" + deviceId;
        // 正向索引 value 改为 "refreshToken:tokenVersion"，刷新时取出版本号与 DB 比对
        String tokenWithValue = refreshToken + ":" + version;
        // Redis维护两套索引
        // 1. REFRESH_INDEX_KEY: refreshToken → "userId:deviceId"
        stringRedisTemplate.opsForValue().set(
                REFRESH_INDEX_KEY + refreshToken, uidDevice,
                jwtProperties.getRefreshTtlDays(), TimeUnit.DAYS);
        // 2. REFRESH_TOKEN_KEY: "userId:deviceId" → "refreshToken:tokenVersion"，用于踢设备时批量删除
        stringRedisTemplate.opsForValue().set(
                REFRESH_TOKEN_KEY + uidDevice, tokenWithValue,
                jwtProperties.getRefreshTtlDays(), TimeUnit.DAYS);
        // 3. TOKEN_VERSION_KEY：保存用户当前token版本号，网关鉴权要比对
        stringRedisTemplate.opsForValue().set(
                TOKEN_VERSION_KEY + userDTO.getId(), version.toString(),
                jwtProperties.getRefreshTtlDays(), TimeUnit.DAYS);
        // 维护用户设备集合，kickAllDevices 用 SMEMBERS 替代 keys() 全库扫描
        stringRedisTemplate.opsForSet().add(LOGIN_DEVICES_KEY + userDTO.getId(), deviceId);
        stringRedisTemplate.expire(LOGIN_DEVICES_KEY + userDTO.getId(),
                jwtProperties.getRefreshTtlDays(), TimeUnit.DAYS);
        return new TokenPair(accessToken, refreshToken, version);
    }

    private Integer getTokenVersion(Long userId) {
        User user = getById(userId);
        return user == null || user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    // 增加积分
    private void addCredits(Long userId, int credits) {
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            info = new UserInfo();
            info.setUserId(userId);
            info.setCredits(UserConstants.DEFAULT_CREDITS);
            info.setLevel(UserConstants.DEFAULT_LEVEL);
        }
        int total = (info.getCredits() == null ? UserConstants.DEFAULT_CREDITS : info.getCredits()) + credits;
        info.setCredits(total);
        info.setLevel(calcLevel(total));
        userInfoService.saveOrUpdate(info);
    }

    @Override
    public Result getMe() {
        return Result.ok(UserHolder.getUser());
    }

    @Override
    public Result queryUserInfo(Long userId) {
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        return Result.ok(info);
    }

    @Override
    public Result queryUserById(Long userId) {
        User user = getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    @Override
    public Result listUserByIds(List<Long> userIdList) {
        List<User> userList = query().in("id", userIdList).list();
        List<UserDTO> dtoList = BeanUtil.copyToList(userList, UserDTO.class);
        return Result.ok(dtoList);
    }

    @Override
    public void addCreditsByOrder(Long userId) {
        addCredits(userId, UserConstants.ORDER_CREDITS);
    }

    @Override
    public void addSignCredits(Long userId) {
        addCredits(userId, UserConstants.SIGN_CREDITS);
    }

    private int calcLevel(int credits) {
        if (credits >= UserConstants.LEVEL_9_CREDITS) return UserConstants.LEVEL_9;
        if (credits >= UserConstants.LEVEL_8_CREDITS) return UserConstants.LEVEL_8;
        if (credits >= UserConstants.LEVEL_7_CREDITS) return UserConstants.LEVEL_7;
        return UserConstants.DEFAULT_LEVEL;
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }
}
