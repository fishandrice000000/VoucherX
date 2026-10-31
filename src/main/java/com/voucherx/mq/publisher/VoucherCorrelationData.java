package com.voucherx.mq.publisher;

import org.springframework.amqp.rabbit.connection.CorrelationData;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 保存 mandatory 消息的退回标记。RabbitMQ 对不可路由的 mandatory 消息会返回 basic.return。
 * 在客户端，basic.return 回调与 publisher confirm 回调可能由不同线程调度，因此增加一个有界等待。
 */
public class VoucherCorrelationData extends CorrelationData {

    private final CountDownLatch returnedLatch = new CountDownLatch(1);
    private volatile boolean returned;
    private volatile String returnReason;

    public VoucherCorrelationData(String id) {
        super(id);
    }

    public void markReturned(String reason) {
        this.returned = true;
        this.returnReason = reason;
        this.returnedLatch.countDown();
    }

    public boolean awaitReturn(long millis) {
        if (returned) {
            return true;
        }
        try {
            return returnedLatch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return returned;
        }
    }

    public boolean isReturned() {
        return returned;
    }

    public String getReturnReason() {
        return returnReason;
    }
}
