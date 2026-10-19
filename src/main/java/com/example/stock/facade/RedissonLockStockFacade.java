package com.example.stock.facade;

import com.example.stock.service.RedisLockStockService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RedissonLockStockFacade {

    private final RedissonClient redissonClient;
    private final RedisLockStockService redisLockStockService;

    public void decrease(Long id, Long quantity) {
        RLock lock = redissonClient.getLock(id.toString());

        boolean available;
        try {
            // 최대 10초 동안 락 획득을 기다리고, 획득하면 1초 뒤 자동 해제된다(leaseTime 지정 시 watchdog 연장 없음)
            available = lock.tryLock(10, 1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // 인터럽트 상태를 복구하고, 락을 얻지 못했으므로 재고 감소 없이 실패시킨다
            Thread.currentThread().interrupt();
            throw new IllegalStateException("락 대기 중 인터럽트가 발생했습니다. id=" + id, e);
        }

        if (!available) {
            throw new IllegalStateException("락 획득에 실패했습니다. id=" + id);
        }

        // 락을 얻은 경우에만 해제한다(얻지 못한 락을 unlock하면 IllegalMonitorStateException)
        try {
            redisLockStockService.decrease(id, quantity);
        } finally {
            lock.unlock();
        }
    }
}
