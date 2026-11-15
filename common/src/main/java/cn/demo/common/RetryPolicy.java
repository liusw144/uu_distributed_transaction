package cn.demo.common;

/**
 * 定义有限次数的指数退避策略，首次失败等待基础间隔，后续间隔不超过上限。
 *
 * @author liusw144
 */
public record RetryPolicy(int maxAttempts, long baseSeconds, long maxSeconds) {
    private static final int BACKOFF_MULTIPLIER = 2;

    public RetryPolicy {
        if (maxAttempts < 1 || baseSeconds < 1 || maxSeconds < baseSeconds) {
            throw new IllegalArgumentException("重试配置必须满足次数>=1，最大间隔>=基础间隔>=1");
        }
    }

    /** 根据已领取次数计算退避秒数，并避免翻倍运算溢出。 */
    public long delaySeconds(int attempts) {
        long delaySeconds = baseSeconds;
        for (int retryIndex = 1; retryIndex < attempts && delaySeconds < maxSeconds; retryIndex++) {
            delaySeconds = delaySeconds > maxSeconds / BACKOFF_MULTIPLIER
                    ? maxSeconds
                    : Math.min(maxSeconds, delaySeconds * BACKOFF_MULTIPLIER);
        }
        return delaySeconds;
    }

    /** 判断已领取次数是否达到最大尝试次数。 */
    public boolean exhausted(int attempts) {
        return attempts >= maxAttempts;
    }
}
