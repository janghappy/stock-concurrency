package com.example.stock.facade;

import com.example.stock.repository.LockRepository;
import com.example.stock.service.NamedLockStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class NamedLockStockFacade {

    private final LockRepository lockRepository;
    private final NamedLockStockService namedLockStockService;

    @Transactional
    public void decrease(Long id, Long quantity){
        String key = id.toString();

        // 락을 얻지 못하면 재고 감소 없이 실패시킨다 (잡지 않은 락은 해제하지 않도록 try 바깥에서 획득)
        Long acquired = lockRepository.getLock(key);
        if (acquired == null || acquired != 1L) {
            throw new IllegalStateException("락 획득에 실패했습니다. key=" + key);
        }

        try{
            namedLockStockService.decrease(id, quantity);
        } finally {
            lockRepository.releaseLock(key);
        }
    }
}
