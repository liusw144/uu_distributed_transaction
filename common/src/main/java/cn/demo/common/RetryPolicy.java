package cn.demo.common;
/** attempts 表示已领取的次数，第一次失败等待 baseSeconds，退避有上限。 */
public record RetryPolicy(int maxAttempts, long baseSeconds, long maxSeconds) {
    public RetryPolicy {
        if (maxAttempts < 1 || baseSeconds < 1 || maxSeconds < baseSeconds)
            throw new IllegalArgumentException("重试配置必须满足次数>=1，最大间隔>=基础间隔>=1");
    }
    public long delaySeconds(int attempts) {
        long delay=baseSeconds;
        for (int i=1; i<attempts && delay<maxSeconds; i++)
            delay=delay>maxSeconds/2 ? maxSeconds : Math.min(maxSeconds,delay*2);
        return delay;
    }
    public boolean exhausted(int attempts) { return attempts>=maxAttempts; }
}
