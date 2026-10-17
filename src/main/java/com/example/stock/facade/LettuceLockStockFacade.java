package com.example.stock.facade;

import com.example.stock.repository.RedisLockRepository;
import com.example.stock.service.RedisLockStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LettuceLockStockFacade {

    private final RedisLockRepository redisLockRepository;
    private final RedisLockStockService redisLockStockService;

    public void decrease(Long id, Long quantity) {
        while (!redisLockRepository.lock(id)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // 인터럽트 상태를 복구하고, 락을 얻지 못했으므로 재고 감소 없이 실패시킨다
                Thread.currentThread().interrupt();
                throw new IllegalStateException("락 대기 중 인터럽트가 발생했습니다. id=" + id, e);
            }
        }

        try {
            redisLockStockService.decrease(id, quantity);
        } finally {
            redisLockRepository.unlock(id);
        }
    }
}
