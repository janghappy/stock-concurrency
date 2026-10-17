package com.example.stock.repository;

import com.example.stock.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LockRepository extends JpaRepository<Stock, Long> {

    // timeout은 초 단위. 반환값 1: 획득, 0: 타임아웃, null: 오류
    @Query(value = "select get_lock(:key, 3)", nativeQuery = true)
    Long getLock(String key);

    @Query(value = "select release_lock(:key)", nativeQuery = true)
    Long releaseLock(String key);

}
