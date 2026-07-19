package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.stream.StreamMessageId;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AbstractStreamConsumer 单元测试
 *
 * <p>测试目标：覆盖 processMessage 方法的 5 个 ACK 分支：
 * <ol>
 *   <li>parsePayload 抛异常 → ACK 丢弃</li>
 *   <li>parsePayload 返回 null → ACK 丢弃</li>
 *   <li>shouldSkip == true（实体已删除场景）→ ACK 丢弃</li>
 *   <li>正常流程：markProcessing → processBusiness → markCompleted → ACK</li>
 *   <li>业务异常 → 重试 / markFailed → ACK</li>
 * </ol>
 *
 * <p>实现策略：
 * <ul>
 *   <li>processMessage 是 private，用反射调用</li>
 *   <li>用 FakeStreamConsumer 子类实现所有抽象方法，记录调用</li>
 *   <li>RedisService 用 Mockito mock，验证 streamAck 是否被调用</li>
 * </ul>
 */
@DisplayName("AbstractStreamConsumer 测试")
class AbstractStreamConsumerTest {

  private RedisService redisService;
  private FakeStreamConsumer consumer;

  @BeforeEach
  void setUp() {
    redisService = mock(RedisService.class);
    consumer = new FakeStreamConsumer(redisService);
  }

  /** 反射调用 private processMessage 方法。 */
  private void invokeProcessMessage(StreamMessageId messageId, Map<String, String> data) throws Exception {
    Method method = AbstractStreamConsumer.class.getDeclaredMethod(
        "processMessage", StreamMessageId.class, Map.class);
    method.setAccessible(true);
    method.invoke(consumer, messageId, data);
  }

  private Map<String, String> payloadData(String resumeId, String content) {
    Map<String, String> data = new HashMap<>();
    data.put("resumeId", resumeId);
    data.put("content", content);
    data.put("retryCount", "0");
    return data;
  }

  // ===========================================================================
  // 分支 1：parsePayload 抛异常 → ACK 丢弃
  // ===========================================================================
  @Nested
  @DisplayName("分支 1：parsePayload 抛异常")
  class ParsePayloadThrowsTests {

    @Test
    @DisplayName("parsePayload 抛 NumberFormatException 时消息应被 ACK 丢弃，不进入业务逻辑")
    void shouldAckAndDiscardWhenParseFails() throws Exception {
      // 给定：parsePayload 会因为 resumeId 非数字抛 NumberFormatException
      Map<String, String> data = new HashMap<>();
      data.put("resumeId", "not-a-number");
      data.put("content", "hello");

      StreamMessageId messageId = new StreamMessageId(1L, 0L);

      // 当：processMessage 处理这条消息
      invokeProcessMessage(messageId, data);

      // 那么：消息被 ACK
      verify(redisService).streamAck(anyString(), anyString(), eq(messageId));
      // 业务方法都没被调用
      assertEquals(0, consumer.processBusinessCount.get());
      assertEquals(0, consumer.markCompletedCount.get());
      assertEquals(0, consumer.retryMessageCount.get());
    }
  }

  // ===========================================================================
  // 分支 2：parsePayload 返回 null → ACK 丢弃
  // ===========================================================================
  @Nested
  @DisplayName("分支 2：parsePayload 返回 null")
  class ParsePayloadReturnsNullTests {

    @Test
    @DisplayName("parsePayload 返回 null（字段缺失）时消息应被 ACK 丢弃")
    void shouldAckWhenPayloadIsNull() throws Exception {
      // 给定：消息缺关键字段，parsePayload 返回 null
      Map<String, String> data = new HashMap<>();
      // 既没有 resumeId 也没有 content → FakeStreamConsumer 返回 null

      StreamMessageId messageId = new StreamMessageId(2L, 0L);

      invokeProcessMessage(messageId, data);

      verify(redisService).streamAck(anyString(), anyString(), eq(messageId));
      assertEquals(0, consumer.processBusinessCount.get());
    }
  }

  // ===========================================================================
  // 分支 3：shouldSkip == true（实体已删除）
  // ===========================================================================
  @Nested
  @DisplayName("分支 3：shouldSkip 返回 true")
  class ShouldSkipTests {

    @Test
    @DisplayName("shouldSkip=true 时消息应被 ACK 丢弃，不调 markProcessing")
    void shouldAckAndSkipWhenShouldSkipTrue() throws Exception {
      consumer.skipNextPayload.set(true);  // 让 shouldSkip 返回 true

      Map<String, String> data = payloadData("100", "content");
      StreamMessageId messageId = new StreamMessageId(3L, 0L);

      invokeProcessMessage(messageId, data);

      verify(redisService).streamAck(anyString(), anyString(), eq(messageId));
      // 关键：markProcessing 不应被调用
      assertEquals(0, consumer.markProcessingCount.get());
      assertEquals(0, consumer.processBusinessCount.get());
    }

    @Test
    @DisplayName("shouldSkip=false 时正常进入业务流程")
    void shouldNotSkipWhenShouldSkipFalse() throws Exception {
      // skipNextPayload 默认 false
      Map<String, String> data = payloadData("100", "content");
      StreamMessageId messageId = new StreamMessageId(4L, 0L);

      invokeProcessMessage(messageId, data);

      verify(redisService).streamAck(anyString(), anyString(), eq(messageId));
      assertEquals(1, consumer.markProcessingCount.get());
      assertEquals(1, consumer.processBusinessCount.get());
      assertEquals(1, consumer.markCompletedCount.get());
    }
  }

  // ===========================================================================
  // 分支 4：正常流程
  // ===========================================================================
  @Nested
  @DisplayName("分支 4：正常处理流程")
  class HappyPathTests {

    @Test
    @DisplayName("正常处理时应按顺序调用 markProcessing → processBusiness → markCompleted → ACK")
    void shouldCallMethodsInOrder() throws Exception {
      Map<String, String> data = payloadData("200", "简历内容");
      StreamMessageId messageId = new StreamMessageId(5L, 0L);

      invokeProcessMessage(messageId, data);

      // ACK 被调用 1 次
      verify(redisService, times(1)).streamAck(anyString(), anyString(), eq(messageId));
      // 业务方法各调用 1 次
      assertEquals(1, consumer.markProcessingCount.get());
      assertEquals(1, consumer.processBusinessCount.get());
      assertEquals(1, consumer.markCompletedCount.get());
      // 不重试、不标记失败
      assertEquals(0, consumer.retryMessageCount.get());
      assertEquals(0, consumer.markFailedCount.get());
    }

    @Test
    @DisplayName("正常处理时 payload 应被正确解析")
    void shouldParsePayloadCorrectly() throws Exception {
      Map<String, String> data = payloadData("300", "特殊简历");
      StreamMessageId messageId = new StreamMessageId(6L, 0L);

      invokeProcessMessage(messageId, data);

      FakePayload processed = consumer.lastProcessedPayload.get();
      assertNotNull(processed);
      assertEquals(300L, processed.resumeId());
      assertEquals("特殊简历", processed.content());
    }
  }

  // ===========================================================================
  // 分支 5：业务异常 → 重试 / markFailed
  // ===========================================================================
  @Nested
  @DisplayName("分支 5：业务异常时的重试与失败")
  class BusinessFailureTests {

    @Test
    @DisplayName("业务异常 + retryCount < MAX 应调 retryMessage 并 ACK")
    void shouldRetryWhenUnderMaxRetry() throws Exception {
      consumer.processBusinessException.set(new RuntimeException("LLM 暂时挂了"));
      // retryCount=0，MAX=3，应该重试

      Map<String, String> data = payloadData("400", "content");
      StreamMessageId messageId = new StreamMessageId(7L, 0L);

      invokeProcessMessage(messageId, data);

      // retryMessage 被调用，参数 retryCount+1=1
      assertEquals(1, consumer.retryMessageCount.get());
      assertEquals(1, (int) consumer.lastRetryCount.get());
      // markFailed 不应被调用
      assertEquals(0, consumer.markFailedCount.get());
      // ACK 仍要调（重试入新消息 + ACK 旧消息）
      verify(redisService).streamAck(anyString(), anyString(), eq(messageId));
      // markCompleted 不应被调用
      assertEquals(0, consumer.markCompletedCount.get());
    }

    @Test
    @DisplayName("业务异常 + retryCount == MAX 应调 markFailed 并 ACK（不重试）")
    void shouldMarkFailedWhenRetryExhausted() throws Exception {
      consumer.processBusinessException.set(new RuntimeException("彻底挂了"));

      Map<String, String> data = payloadData("500", "content");
      data.put("retryCount", String.valueOf(AsyncTaskStreamConstants.MAX_RETRY_COUNT));  // retryCount=3=MAX
      StreamMessageId messageId = new StreamMessageId(8L, 0L);

      invokeProcessMessage(messageId, data);

      // markFailed 被调用
      assertEquals(1, consumer.markFailedCount.get());
      // retryMessage 不应被调用
      assertEquals(0, consumer.retryMessageCount.get());
      // ACK 仍要调
      verify(redisService).streamAck(anyString(), anyString(), eq(messageId));
    }

    /**
     * off-by-one 边界：retryCount=MAX-1（=2）时仍应重试，
     * retryCount=MAX（=3）时应 markFailed。
     */
    @Test
    @DisplayName("off-by-one：retryCount=MAX-1 仍重试，retryCount=MAX 标记失败")
    void shouldHandleOffByOneBoundary() throws Exception {
      consumer.processBusinessException.set(new RuntimeException("失败"));

      // retryCount=MAX-1=2，应该重试
      Map<String, String> dataRetry = payloadData("600", "content");
      dataRetry.put("retryCount", String.valueOf(AsyncTaskStreamConstants.MAX_RETRY_COUNT - 1));
      invokeProcessMessage(new StreamMessageId(9L, 0L), dataRetry);
      assertEquals(1, consumer.retryMessageCount.get(), "retryCount=MAX-1 时应该重试");

      // 重置计数
      consumer.retryMessageCount.set(0);
      consumer.markFailedCount.set(0);

      // retryCount=MAX=3，应该 markFailed
      Map<String, String> dataFail = payloadData("601", "content");
      dataFail.put("retryCount", String.valueOf(AsyncTaskStreamConstants.MAX_RETRY_COUNT));
      invokeProcessMessage(new StreamMessageId(10L, 0L), dataFail);
      assertEquals(0, consumer.retryMessageCount.get(), "retryCount=MAX 时不应重试");
      assertEquals(1, consumer.markFailedCount.get(), "retryCount=MAX 时应标记失败");
    }
  }

  // ===========================================================================
  // parseRetryCount 边界
  // ===========================================================================
  @Nested
  @DisplayName("parseRetryCount 字段解析")
  class ParseRetryCountTests {

    @Test
    @DisplayName("retryCount 缺失时默认 0")
    void shouldDefaultToZeroWhenMissing() throws Exception {
      Map<String, String> data = new HashMap<>();
      data.put("resumeId", "1");
      data.put("content", "x");
      // 不放 retryCount

      invokeProcessMessage(new StreamMessageId(11L, 0L), data);

      // 正常处理（retryCount=0），未触发重试逻辑
      assertEquals(1, consumer.markCompletedCount.get());
    }

    @Test
    @DisplayName("retryCount 为非数字字符串时回退 0")
    void shouldFallbackToZeroForInvalidValue() throws Exception {
      Map<String, String> data = payloadData("1", "x");
      data.put("retryCount", "abc");  // 非数字

      invokeProcessMessage(new StreamMessageId(12L, 0L), data);

      // parseRetryCount 返回 0，正常流程
      assertEquals(1, consumer.markCompletedCount.get());
    }

    @Test
    @DisplayName("data 为 null 时 parseRetryCount 应返回 0（不 NPE）")
    void shouldReturnZeroWhenDataIsNull() throws Exception {
      // data=null → parsePayload 会返回 null（FakeStreamConsumer 处理 null）
      // 走分支 2，直接 ACK
      invokeProcessMessage(new StreamMessageId(13L, 0L), null);

      verify(redisService).streamAck(anyString(), anyString(), any());
      assertEquals(0, consumer.processBusinessCount.get());
    }
  }

  // ===========================================================================
  // truncateError 边界
  // ===========================================================================
  @Nested
  @DisplayName("truncateError 截断")
  class TruncateErrorTests {

    @Test
    @DisplayName("null 输入应返回 null")
    void shouldReturnNullForNull() {
      assertNull(consumer.truncateError(null));
    }

    @Test
    @DisplayName("短文本应原样返回")
    void shouldReturnShortTextUnchanged() {
      String shortText = "短错误";
      assertEquals(shortText, consumer.truncateError(shortText));
    }

    @Test
    @DisplayName("超过 500 字符应截断到 500")
    void shouldTruncateLongText() {
      String longText = "a".repeat(800);
      String result = consumer.truncateError(longText);
      assertEquals(500, result.length());
    }

    @Test
    @DisplayName("恰好 500 字符不应截断")
    void shouldNotTruncateAtBoundary() {
      String exact = "b".repeat(500);
      assertEquals(500, consumer.truncateError(exact).length());
      assertEquals(exact, consumer.truncateError(exact));
    }
  }

  // ===========================================================================
  // 测试专用子类
  // ===========================================================================

  /** 测试用 payload。 */
  record FakePayload(long resumeId, String content) {}

  /**
   * FakeStreamConsumer：实现所有抽象方法，记录调用次数和参数。
   * 不重写 init/shutdown，避免启动真实线程。
   */
  static class FakeStreamConsumer extends AbstractStreamConsumer<FakePayload> {

    final AtomicInteger markProcessingCount = new AtomicInteger(0);
    final AtomicInteger processBusinessCount = new AtomicInteger(0);
    final AtomicInteger markCompletedCount = new AtomicInteger(0);
    final AtomicInteger markFailedCount = new AtomicInteger(0);
    final AtomicInteger retryMessageCount = new AtomicInteger(0);
    final AtomicInteger lastRetryCount = new AtomicInteger(-1);
    final AtomicReference<FakePayload> lastProcessedPayload = new AtomicReference<>();

    /** 控制行为：设了之后 processBusiness 抛这个异常。 */
    final AtomicReference<RuntimeException> processBusinessException = new AtomicReference<>();
    /** 控制行为：设了之后 shouldSkip 返回 true。 */
    final AtomicBoolean skipNextPayload = new AtomicBoolean(false);

    FakeStreamConsumer(RedisService redisService) {
      super(redisService);
    }

    @Override
    protected String taskDisplayName() { return "fake-task"; }

    @Override
    protected String streamKey() { return "stream:fake"; }

    @Override
    protected String groupName() { return "fake-group"; }

    @Override
    protected String consumerPrefix() { return "fake-consumer-"; }

    @Override
    protected String threadName() { return "fake-thread"; }

    @Override
    protected FakePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
      if (data == null) return null;
      String resumeIdStr = data.get("resumeId");
      String content = data.get("content");
      if (resumeIdStr == null || content == null) {
        return null;
      }
      // 让非数字的 resumeId 抛 NumberFormatException（测分支 1）
      return new FakePayload(Long.parseLong(resumeIdStr), content);
    }

    @Override
    protected String payloadIdentifier(FakePayload payload) {
      return "resume-" + payload.resumeId();
    }

    @Override
    protected boolean shouldSkip(FakePayload payload) {
      return skipNextPayload.get();
    }

    @Override
    protected void markProcessing(FakePayload payload) {
      markProcessingCount.incrementAndGet();
    }

    @Override
    protected void processBusiness(FakePayload payload) {
      lastProcessedPayload.set(payload);
      processBusinessCount.incrementAndGet();
      RuntimeException ex = processBusinessException.get();
      if (ex != null) throw ex;
    }

    @Override
    protected void markCompleted(FakePayload payload) {
      markCompletedCount.incrementAndGet();
    }

    @Override
    protected void markFailed(FakePayload payload, String error) {
      markFailedCount.incrementAndGet();
    }

    @Override
    protected void retryMessage(FakePayload payload, int retryCount) {
      retryMessageCount.incrementAndGet();
      lastRetryCount.set(retryCount);
    }
  }
}
