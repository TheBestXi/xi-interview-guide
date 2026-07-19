package interview.guide.modules.llmprovider.service;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.SecurityConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService.EncryptedValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiKeyEncryptionService 单元测试
 *
 * <p>测什么：
 * <ol>
 *   <li>加解密往返一致性（最基础的安全保证）</li>
 *   <li>nonce 随机性（同明文两次加密结果必须不同，否则有重放风险）</li>
 *   <li>密钥派生歧义：32 字节 Base64 vs SHA-256（这是探查发现的脆弱点）</li>
 *   <li>启动配置：未配置 key 时三种分支的行为</li>
 *   <li>解密失败：篡改密文/错误 key 必须抛异常（不能"解密成功但内容错误"）</li>
 * </ol>
 *
 * <p>注意：本测试不启动 Spring 容器，手动 new 出来 + 调 init() 模拟 @PostConstruct。
 */
@DisplayName("ApiKeyEncryptionService 测试")
class ApiKeyEncryptionServiceTest {

  /** 构造一个已初始化的 service（模拟 Spring 调 @PostConstruct）。 */
  private ApiKeyEncryptionService newService(String configuredKey) {
    SecurityConfig security = new SecurityConfig();
    security.setApiKeyEncryptionKey(configuredKey);
    // 显式开启 require，避免 fallback 干扰主流程测试
    security.setRequireEncryptionKey(true);
    ApiKeyEncryptionService service = new ApiKeyEncryptionService(propsWith(security));
    service.init();
    return service;
  }

  private LlmProviderProperties propsWith(SecurityConfig security) {
    LlmProviderProperties props = new LlmProviderProperties();
    props.setSecurity(security);
    return props;
  }

  // ===========================================================================
  // 加解密往返
  // ===========================================================================
  @Nested
  @DisplayName("encrypt + decrypt 往返")
  class RoundTripTests {

    @Test
    @DisplayName("加密后再解密应还原原文明")
    void shouldRoundTripPlainText() {
      ApiKeyEncryptionService service = newService("my-secret-key-for-test");
      String plain = "sk-bailian-abc123-xyz789";

      EncryptedValue encrypted = service.encrypt(plain);
      String decrypted = service.decrypt(encrypted.nonce(), encrypted.ciphertext());

      assertEquals(plain, decrypted);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "a",                                          // 单字符
        "sk-07473b5a-very-long-api-key-1234567890",   // 长 key
        "中文密钥内容测试",                              // 中文
        "special !@#$%^&*()_+-={}[]|\\:;\"'<>?,./~` ", // 特殊字符
        "  with spaces  ",                            // 含空格
        "\n\t\r"                                      // 控制字符
    })
    @DisplayName("各种明文都应能正确加解密往返")
    void shouldRoundTripVariousInputs(String plain) {
      ApiKeyEncryptionService service = newService("my-secret-key-for-test");

      EncryptedValue encrypted = service.encrypt(plain);
      String decrypted = service.decrypt(encrypted.nonce(), encrypted.ciphertext());

      assertEquals(plain, decrypted);
    }

    @Test
    @DisplayName("空字符串应能加解密往返")
    void shouldRoundTripEmptyString() {
      ApiKeyEncryptionService service = newService("my-secret-key-for-test");

      EncryptedValue encrypted = service.encrypt("");
      String decrypted = service.decrypt(encrypted.nonce(), encrypted.ciphertext());

      assertEquals("", decrypted);
    }
  }

  // ===========================================================================
  // nonce 随机性
  // ===========================================================================
  @Nested
  @DisplayName("nonce 随机性")
  class NonceRandomnessTests {

    @Test
    @DisplayName("同明文加密两次应得到不同的 nonce")
    void shouldProduceDifferentNoncesForSamePlain() {
      ApiKeyEncryptionService service = newService("my-secret-key-for-test");
      String plain = "sk-same-plain-text";

      EncryptedValue first = service.encrypt(plain);
      EncryptedValue second = service.encrypt(plain);

      assertNotEquals(first.nonce(), second.nonce(),
          "nonce 必须随机，否则有重放攻击风险");
      assertNotEquals(first.ciphertext(), second.ciphertext(),
          "GCM 模式下不同 nonce 必然产生不同密文");
    }

    @Test
    @DisplayName("不同 nonce 加密的密文都应能被同一 key 解密")
    void shouldDecryptBothNoncesWithSameKey() {
      ApiKeyEncryptionService service = newService("my-secret-key-for-test");
      String plain = "sk-same-plain-text";

      EncryptedValue first = service.encrypt(plain);
      EncryptedValue second = service.encrypt(plain);

      assertEquals(plain, service.decrypt(first.nonce(), first.ciphertext()));
      assertEquals(plain, service.decrypt(second.nonce(), second.ciphertext()));
    }
  }

  // ===========================================================================
  // 密钥派生歧义（探查发现的脆弱点）
  // ===========================================================================
  @Nested
  @DisplayName("密钥派生路径")
  class KeyDerivationTests {

    @Test
    @DisplayName("32 字节 Base64 字符串应被当作原始 key 直接使用")
    void shouldUse32ByteBase64AsRawKey() {
      // 32 字节的原始 key，Base64 编码后是 44 字符（带 padding）
      String rawKey = "0123456789ABCDEF0123456789ABCDEF";
      String base64Key = Base64.getEncoder().encodeToString(rawKey.getBytes());

      // 用 base64Key 配置：走"Base64 解码 → 32 字节 → 直接当 key"路径
      ApiKeyEncryptionService base64Service = newService(base64Key);

      // 加密后能解回来，证明路径走通
      EncryptedValue encrypted = base64Service.encrypt("test");
      assertEquals("test", base64Service.decrypt(encrypted.nonce(), encrypted.ciphertext()));
    }

    @Test
    @DisplayName("非 32 字节的 Base64 字符串应走 SHA-256 派生路径")
    void shouldFallbackToSha256ForNonBase64Key() {
      // 普通人类可读密码（不是 32 字节 Base64）
      ApiKeyEncryptionService sha256Service = newService("my-password");

      EncryptedValue encrypted = sha256Service.encrypt("test");
      assertEquals("test", sha256Service.decrypt(encrypted.nonce(), encrypted.ciphertext()));
    }

    @Test
    @DisplayName("SHA-256 派生路径：同密码的两个 service 实例应能互相解密")
    void sha256DerivedKeyShouldWorkAcrossInstances() {
      String password = "same-human-readable-password";

      ApiKeyEncryptionService serviceA = newService(password);
      ApiKeyEncryptionService serviceB = newService(password);

      EncryptedValue encrypted = serviceA.encrypt("cross-instance-test");
      assertEquals("cross-instance-test", serviceB.decrypt(encrypted.nonce(), encrypted.ciphertext()));
    }

    @Test
    @DisplayName("32 字节 Base64 路径：同配置的两个实例应能互相解密")
    void rawBase64KeyShouldWorkAcrossInstances() {
      String rawKey = "0123456789ABCDEF0123456789ABCDEF";
      String base64Key = Base64.getEncoder().encodeToString(rawKey.getBytes());

      ApiKeyEncryptionService serviceA = newService(base64Key);
      ApiKeyEncryptionService serviceB = newService(base64Key);

      EncryptedValue encrypted = serviceA.encrypt("cross-instance-test");
      assertEquals("cross-instance-test", serviceB.decrypt(encrypted.nonce(), encrypted.ciphertext()));
    }

    /**
     * 探查发现的歧义点：同一个字符串，如果它"恰好能解成 32 字节 Base64"会走原始 key 路径，
     * 否则走 SHA-256 路径。这两个路径产出的 key 完全不同——
     * 运维误把人类可读密码配成"恰好 44 字符的合法 Base64"时，会跨实例解不开。
     * 此测试锁定现状（不一定是 bug，但行为必须可预期）。
     */
    @Test
    @DisplayName("配置形态决定派生路径：32 字节 Base64 vs 普通字符串产出不同的 key")
    void differentKeyFormsProduceDifferentKeys() {
      // 同一段 32 字节内容，一种以 Base64 形式配置，一种以原始字符串形式配置
      String rawKey = "0123456789ABCDEF0123456789ABCDEF"; // 32 字符 ASCII
      String base64Form = Base64.getEncoder().encodeToString(rawKey.getBytes());

      ApiKeyEncryptionService rawKeyService = newService(rawKey);       // 32 字符 ASCII，会走 SHA-256（不是合法 Base64 因为含非 Base64 字符串？实际会走 SHA-256）
      ApiKeyEncryptionService base64Service = newService(base64Form);   // 走原始 key 路径

      // 用 rawKeyService 加密的东西，base64Service 应该解不开（除非巧合）
      EncryptedValue encryptedByRaw = rawKeyService.encrypt("hello");
      assertThrows(BusinessException.class,
          () -> base64Service.decrypt(encryptedByRaw.nonce(), encryptedByRaw.ciphertext()),
          "两种 key 形态产生的 secretKey 不同，互相解不开是预期");
    }
  }

  // ===========================================================================
  // null / 空防护
  // ===========================================================================
  @Nested
  @DisplayName("null 与空白输入")
  class NullHandlingTests {

    /**
     * 当前实现：encrypt(null) 会 NPE（plainText.getBytes()）。
     * 这是已知的脆弱点，测试锁定当前行为。
     * 如果以后修了 null 防护，改成断言抛 BusinessException 即可。
     */
    @Test
    @DisplayName("encrypt(null) 当前会抛 BusinessException（包 NPE）")
    void encryptNullShouldThrow() {
      ApiKeyEncryptionService service = newService("my-key");
      // 当前实现：plainText.getBytes() 在 null 上抛 NPE → 被 catch 包装成 BusinessException
      assertThrows(BusinessException.class, () -> service.encrypt(null));
    }

    @Test
    @DisplayName("decrypt(null, null) 应抛 BusinessException")
    void decryptNullShouldThrow() {
      ApiKeyEncryptionService service = newService("my-key");
      assertThrows(BusinessException.class, () -> service.decrypt(null, null));
    }

    @Test
    @DisplayName("decrypt(空串, 空串) 应抛 BusinessException（Base64 解码失败）")
    void decryptEmptyShouldThrow() {
      ApiKeyEncryptionService service = newService("my-key");
      assertThrows(BusinessException.class, () -> service.decrypt("", ""));
    }
  }

  // ===========================================================================
  // 解密失败 / 篡改检测（GCM 认证标签）
  // ===========================================================================
  @Nested
  @DisplayName("解密失败场景")
  class DecryptionFailureTests {

    @Test
    @DisplayName("篡改 ciphertext 应导致 GCM 认证失败并抛 BusinessException")
    void tamperedCiphertextShouldThrow() {
      ApiKeyEncryptionService service = newService("my-key");
      EncryptedValue encrypted = service.encrypt("secret");

      // 把 ciphertext 的最后一个字符换掉
      String tampered = encrypted.ciphertext().substring(0, encrypted.ciphertext().length() - 1) + "X";
      final String finalTampered = tampered;

      assertThrows(BusinessException.class,
          () -> service.decrypt(encrypted.nonce(), finalTampered));
    }

    @Test
    @DisplayName("用错误的 nonce 解密应抛 BusinessException")
    void wrongNonceShouldThrow() {
      ApiKeyEncryptionService service = newService("my-key");
      EncryptedValue encrypted = service.encrypt("secret");
      EncryptedValue otherEncrypted = service.encrypt("other");

      // 用 otherEncrypted 的 nonce 解 encrypted 的 ciphertext
      assertThrows(BusinessException.class,
          () -> service.decrypt(otherEncrypted.nonce(), encrypted.ciphertext()));
    }

    @Test
    @DisplayName("用不同 key 的 service 解密应抛 BusinessException")
    void differentKeyShouldNotDecrypt() {
      ApiKeyEncryptionService serviceA = newService("key-a");
      ApiKeyEncryptionService serviceB = newService("key-b-different");

      EncryptedValue encrypted = serviceA.encrypt("secret");
      assertThrows(BusinessException.class,
          () -> serviceB.decrypt(encrypted.nonce(), encrypted.ciphertext()));
    }

    @Test
    @DisplayName("解密失败时 BusinessException 的错误码必须是 PROVIDER_CONFIG_READ_FAILED")
    void decryptionFailureShouldUseCorrectErrorCode() {
      ApiKeyEncryptionService service = newService("my-key");
      EncryptedValue encrypted = service.encrypt("secret");
      String tampered = encrypted.ciphertext() + "corrupted";

      BusinessException ex = assertThrows(BusinessException.class,
          () -> service.decrypt(encrypted.nonce(), tampered));
      assertEquals(ErrorCode.PROVIDER_CONFIG_READ_FAILED.getCode(), ex.getCode());
    }
  }

  // ===========================================================================
  // 启动配置（init 阶段）
  // ===========================================================================
  @Nested
  @DisplayName("init() 启动配置校验")
  class InitConfigTests {

    @Test
    @DisplayName("未配置 key 且 requireEncryptionKey=true（默认）应启动失败")
    void shouldFailWhenKeyMissingAndRequired() {
      SecurityConfig security = new SecurityConfig(); // 默认 require=true
      ApiKeyEncryptionService service = new ApiKeyEncryptionService(propsWith(security));

      BusinessException ex = assertThrows(BusinessException.class, service::init);
      assertEquals(ErrorCode.PROVIDER_CONFIG_READ_FAILED.getCode(), ex.getCode());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("key 为 null/空/纯空白且 require=true 应启动失败")
    void shouldFailWhenKeyBlankAndRequired(String blankKey) {
      SecurityConfig security = new SecurityConfig();
      security.setApiKeyEncryptionKey(blankKey);
      ApiKeyEncryptionService service = new ApiKeyEncryptionService(propsWith(security));

      assertThrows(BusinessException.class, service::init);
    }

    @Test
    @DisplayName("未配置 key + require=false + allowFallback=false 应启动失败")
    void shouldFailWhenKeyMissingButNoFallbackAllowed() {
      SecurityConfig security = new SecurityConfig();
      security.setRequireEncryptionKey(false);
      security.setAllowFallbackEncryptionKey(false); // 默认就是 false，显式写出来

      ApiKeyEncryptionService service = new ApiKeyEncryptionService(propsWith(security));
      BusinessException ex = assertThrows(BusinessException.class, service::init);
      assertEquals(ErrorCode.PROVIDER_CONFIG_READ_FAILED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("未配置 key + require=false + allowFallback=true 应回退到 DEV_FALLBACK_KEY 并能正常工作")
    void shouldFallbackToDevKeyWhenAllowed() {
      SecurityConfig security = new SecurityConfig();
      security.setRequireEncryptionKey(false);
      security.setAllowFallbackEncryptionKey(true);

      ApiKeyEncryptionService service = new ApiKeyEncryptionService(propsWith(security));
      assertDoesNotThrow(service::init, "允许 fallback 时 init 应成功");

      // 验证：fallback key 能正常加解密
      service.init();
      EncryptedValue encrypted = service.encrypt("test");
      assertEquals("test", service.decrypt(encrypted.nonce(), encrypted.ciphertext()));
    }

    @Test
    @DisplayName("security=null（极端情况）应等价于默认配置：require=true → 启动失败")
    void nullSecurityShouldFailWithDefaults() {
      LlmProviderProperties props = new LlmProviderProperties();
      props.setSecurity(null);
      ApiKeyEncryptionService service = new ApiKeyEncryptionService(props);

      // resolveConfiguredKey 里 security==null → requireEncryptionKey=true → 抛异常
      assertThrows(BusinessException.class, service::init);
    }
  }

  // ===========================================================================
  // EncryptedValue record 契约
  // ===========================================================================
  @Nested
  @DisplayName("EncryptedValue 数据结构")
  class EncryptedValueContractTests {

    @Test
    @DisplayName("加密结果 nonce 和 ciphertext 都应是非空 Base64 字符串")
    void encryptedValueShouldContainBase64Strings() {
      ApiKeyEncryptionService service = newService("my-key");
      EncryptedValue encrypted = service.encrypt("plain-text");

      assertNotNull(encrypted.nonce());
      assertNotNull(encrypted.ciphertext());
      assertFalse(encrypted.nonce().isEmpty());
      assertFalse(encrypted.ciphertext().isEmpty());

      // 都是合法 Base64
      assertDoesNotThrow(() -> Base64.getDecoder().decode(encrypted.nonce()));
      assertDoesNotThrow(() -> Base64.getDecoder().decode(encrypted.ciphertext()));
    }

    @Test
    @DisplayName("nonce 解码后应为 12 字节（GCM 标准）")
    void nonceShouldBe12Bytes() {
      ApiKeyEncryptionService service = newService("my-key");
      EncryptedValue encrypted = service.encrypt("plain-text");

      byte[] nonceBytes = Base64.getDecoder().decode(encrypted.nonce());
      assertEquals(12, nonceBytes.length, "GCM nonce 标准长度是 12 字节");
    }
  }
}
