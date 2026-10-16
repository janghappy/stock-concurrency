# 프로젝트 설명 — 재고시스템으로 알아보는 동시성이슈 해결방법

> [README](../README.md)로 돌아가기

## 목적

재고 감소처럼 **공유 데이터를 조회 후 변경하는 로직**에 동시 요청이 몰리면 데이터 정합성이 어떻게 깨지는지 확인하고,
이를 해결하는 **동시성 제어 기법**을 직접 구현하며 각각의 특징과 한계를 비교한다.

- 재고 감소 로직 구현 → 동시 요청 테스트로 Race Condition 확인
- Java `synchronized` 적용과 한계 확인
- Database(MySQL) 락 적용: Pessimistic Lock, Optimistic Lock, Named Lock
- Redis 분산 락 적용: Lettuce(spin lock), Redisson(pub/sub)

## 기능

- 상품별 재고 저장
- 재고 감소 (요청 수량만큼 차감)

## 주요 요구사항

- 재고는 **0 미만으로 내려갈 수 없다.** (부족하면 예외)
- 동시에 여러 요청이 들어와도 **요청한 만큼 정확히 차감**되어야 한다.
  (재고 100개에 1개씩 100건을 동시에 요청하면 재고는 0이 되어야 한다.)

## 기술 스펙

| 구분 | 사용 기술 |
|---|---|
| Language | Java 11 |
| Framework | Spring Boot 2.7 (Spring Web, Spring Data JPA) |
| Database | MySQL (Docker) |
| Lock | Redis — Lettuce(Spring Data Redis), Redisson |
| Build | Gradle |
| 기타 | Lombok |

## 프로젝트 구조

강의 소스 기준으로 만들어 갈 구조이며, 구현하면서 갱신한다. 테스트는 방법(서비스·Facade)마다 `XxxTest` 클래스를 따로 둔다.

```
stock-concurrency
└── src
    ├── main/java/com/example/stock
    │   ├── StockApplication
    │   ├── domain        # Stock(productId, quantity, @Version) — decrease()에서 수량 검증
    │   ├── repository    # StockRepository(비관적 락·낙관적 락 조회), LockRepository(Named Lock), RedisLockRepository(Lettuce)
    │   ├── service       # StockService(synchronized), PessimisticLockStockService, OptimisticLockStockService
    │   └── facade        # OptimisticLockStockFacade(재시도), NamedLockStockFacade, LettuceLockStockFacade, RedissonLockStockFacade
    └── test/java/com/example/stock
        └── ...           # 방법별 동시 요청 테스트 (100 스레드 → 재고 0 검증)
```

## 실행 방법

### 1. MySQL 실행

```bash
docker run -d -p 3306:3306 -e MYSQL_ROOT_PASSWORD=1234 --name mysql mysql
docker exec -it mysql mysql -uroot -p1234 -e "CREATE DATABASE stock_example;"
```

### 2. Redis 실행 (5·6단계부터)

```bash
sudo apt install redis-server        # 최초 1회
sudo systemctl start redis-server
redis-cli ping                       # PONG 이면 정상
```

### 3. 설정 파일 생성

`application.yaml`은 Git에서 관리하지 않는다(`.gitignore`, 로컬 전용).
Git에는 DB 접속 정보(url·username·password)를 비워 둔 `application.yaml.example`만 올라가 있으므로, 복사한 뒤 접속 정보를 채운다.

```bash
cp src/main/resources/application.yaml.example \
   src/main/resources/application.yaml
# url: jdbc:mysql://127.0.0.1:3306/stock_example, username, password 입력
```

- `spring.jpa.hibernate.ddl-auto: create` — 기동할 때마다 테이블을 새로 만든다.
- Named Lock 단계에서는 락용 커넥션을 따로 쓰므로 `spring.datasource.hikari.maximum-pool-size`를 넉넉히(예: 40) 늘린다.

### 4. 테스트 실행

```bash
./gradlew test
```

> 테스트는 실제 MySQL(·Redis)에 붙는 `@SpringBootTest`이므로 위 1~3번이 먼저 되어 있어야 한다.

## 동시성 테스트 방식

모든 방법을 같은 조건으로 검증한다.

- `@BeforeEach`: 재고 100개짜리 상품을 저장하고 그 `id`를 `stockId`에 보관 / `@AfterEach`: 전체 삭제
- 스레드 32개짜리 `ExecutorService` 위에서 `CompletableFuture.runAsync`로 재고 1개 감소 요청 100건을 동시에 보내고, `allOf(...).orTimeout(10초).join()`으로 모두 끝날 때까지 기다린다.
- 남은 재고가 **0**인지 확인한다. (동시성 제어가 없으면 0보다 큰 값이 남아 실패한다.)

## 구현 상세

각 단계를 구현하면서 아래에 정리한다. 강의 소스와 다르게 구현한 부분은 차이점과 이유를 함께 적는다.

### 0. 재고 감소 로직과 Race Condition

- `Stock.decrease()`: 남는 수량이 0 미만이면 예외를 던지고, 아니면 수량을 차감한다.
- `StockService.decrease()`: `@Transactional` 안에서 `findById` → `decrease` → `saveAndFlush`.
  트랜잭션 안에서는 변경 감지로 UPDATE가 나가므로 `saveAndFlush`가 꼭 필요하지는 않다.
  다만 다음 단계(`synchronized`)에서 `@Transactional`을 떼면 엔티티가 준영속 상태가 되어 `save`가 필요해지므로 강의처럼 남겨 둔다.
- 결과: `재고감소`(단건)는 통과하고, `동시에_100개의_요청`은 **실패**한다(예: `expected: <0> but was: <90>`).
  동시성 제어가 없어 여러 스레드가 같은 수량을 읽고 각자 저장하면서 감소분이 유실된다. 남는 값은 실행할 때마다 다르다.

**강의 소스와 다른 점 (테스트)**

| 항목 | 강의 | 이 프로젝트 | 이유 |
|---|---|---|---|
| 조회 id | `findById(1L)` 고정 | `@BeforeEach`에서 저장한 `stockId` | `deleteAll()`은 AUTO_INCREMENT를 초기화하지 않아, 클래스 전체를 실행하면 두 번째 테스트부터 id가 2 이상이 된다. |
| 동시 실행 | `ExecutorService` + `CountDownLatch` | `CompletableFuture.runAsync(..., executorService)` + `allOf().join()` | 더 간결하고, 스레드 안의 예외가 `join()`에서 드러난다. (`submit()`은 예외를 `Future`에 담아 두고 아무도 꺼내지 않으므로 묻힌다.) |
| 대기 제한 | 없음 (`latch.await()`) | `orTimeout(10, SECONDS)` + `finally`에서 `shutdown()` | 락 구현 버그로 데드락이 나도 테스트가 무한정 멈추지 않게 한다. |

> `runAsync`에 풀을 넘기지 않으면 공용 ForkJoinPool(동시 실행 수 = CPU 코어 수 − 1)을 쓰게 되어 경합이 약해지므로 반드시 넘긴다.

### 1. `synchronized`

`StockService.decrease()`에 `synchronized`를 붙여 한 번에 한 스레드만 `조회 → 감소 → 저장`을 실행하게 한다.
`@Transactional`은 제거하고(주석 처리), `saveAndFlush`로 변경을 직접 반영한다.

**주의: `@Transactional`을 유지하면 `synchronized`를 붙여도 해결되지 않는다**

`@Transactional`은 Spring이 만든 프록시가 메서드 **바깥**에서 트랜잭션을 시작하고 커밋한다.

```
프록시: 트랜잭션 시작
  └── StockService.decrease()   ← synchronized 락 구간 (락 획득 ~ 해제)
프록시: 커밋                      ← 락이 이미 풀린 뒤
```

락이 풀린 뒤 커밋되기 전 사이에 다른 스레드가 `decrease()`에 들어오면, 아직 커밋되지 않은 **이전 수량**을 읽는다.
결국 두 스레드가 같은 값을 기준으로 차감하게 되어 감소분이 유실되고, 동시성 테스트는 여전히 실패한다.
그래서 이 단계에서는 `@Transactional`을 떼어 낸다. 트랜잭션이 없으면 `findById`로 받은 엔티티가 준영속 상태라 변경 감지가 동작하지 않으므로, `saveAndFlush`로 직접 저장해야 한다.

**장단점**

| 구분 | 내용 |
|---|---|
| 장점 | 키워드 하나로 구현할 수 있을 만큼 **단순**하다. |
| 장점 | DB 락·Redis 같은 **추가 인프라가 필요 없다.** |
| 장점 | 서버가 1대라면 동시 요청을 확실히 직렬화한다. |
| 단점 | **JVM(프로세스) 로컬 락**이다. 서버가 2대 이상이면 각 서버가 자기 락만 알기 때문에, 서로 다른 서버로 들어온 요청은 동시에 같은 행을 수정해 다시 감소분이 유실된다. 실제 운영 환경은 대부분 다중 인스턴스라 사실상 쓰기 어렵다. |
| 단점 | `@Transactional`과 함께 쓸 수 없다. 조회와 저장이 각각 별도 트랜잭션으로 실행되어, 다른 작업과 하나의 트랜잭션으로 묶을 수 없다. |
| 단점 | 락 단위가 **빈 인스턴스(`this`) 전체**라, 상품 1의 재고 감소가 상품 2의 재고 감소까지 막는다. 상품이 많을수록 처리량이 떨어진다. |
| 단점 | 락 대기 타임아웃이나 공정성(fairness)을 제어할 수 없다. |

### 2. Pessimistic Lock

재고를 조회할 때 DB 행에 **배타 락**을 걸어, 락을 잡은 트랜잭션이 커밋할 때까지 다른 트랜잭션이 같은 행을 읽어 수정하지 못하게 한다.

```java
// StockRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select s from Stock s where s.id = :id")
Stock findByIdWithPessimisticLock(Long id);

// PessimisticLockStockService
@Transactional
public void decrease(Long id, Long quantity) {
    Stock stock = stockRepository.findByIdWithPessimisticLock(id);
    stock.decrease(quantity);
    stockRepository.save(stock);
}
```

실행되는 쿼리 (테스트 로그):

```sql
select ... from stock stock0_ where stock0_.id=? for update
```

- 락은 **트랜잭션이 끝날 때(커밋·롤백) DB가 해제**한다. `synchronized`와 달리 락 구간이 커밋까지 포함되므로 `@Transactional`을 그대로 쓴다.
- `@Transactional` 안이므로 `save`는 없어도 변경 감지로 반영된다. 강의 코드처럼 남겨 두었다.
- 테스트는 `PessimisticLockStockServiceTest`로 분리했다. `StockServiceTest`와 같은 내용이며 대상 서비스만 다르다.

**장단점**

| 구분 | 내용 |
|---|---|
| 장점 | 락을 DB가 관리하므로 **서버가 여러 대여도 동작**한다. |
| 장점 | 락 단위가 **행**이라, 다른 상품의 재고 감소는 막지 않는다. |
| 장점 | 충돌이 잦을 때는 재시도가 반복되는 Optimistic Lock보다 성능이 나을 수 있다. |
| 장점 | `@Transactional`과 함께 쓸 수 있고, 별도 재시도 로직 없이 정합성이 확실하다. |
| 단점 | 락을 기다리는 동안 트랜잭션과 DB 커넥션을 붙잡고 있어 **처리량이 떨어진다.** 트랜잭션이 길수록 심해진다. |
| 단점 | 여러 행을 서로 다른 순서로 잠그면 **데드락**이 날 수 있다. |
| 단점 | 기본 대기 시간이 길다(MySQL `innodb_lock_wait_timeout` 기본 50초). 필요하면 `@QueryHints`의 `javax.persistence.lock.timeout`으로 조정한다. |

### 3. Optimistic Lock

락을 잡지 않고, **버전 컬럼**으로 갱신 시점에 충돌을 감지한다. 충돌하면 예외가 나므로 **재시도를 직접 구현**해야 한다.

```java
// Stock
@Version
private Long version;

// StockRepository
@Lock(LockModeType.OPTIMISTIC)
@Query("select s from Stock s where s.id = :id")
Stock findByIdWithOptimisticLock(Long id);

// OptimisticLockStockService — 한 번 시도하는 트랜잭션
@Transactional
public void decrease(Long id, Long quantity) {
    Stock stock = stockRepository.findByIdWithOptimisticLock(id);
    stock.decrease(quantity);
    stockRepository.save(stock);
}

// OptimisticLockStockFacade — 실패하면 50ms 쉬고 다시 시도
public void decrease(Long id, Long quantity) throws InterruptedException {
    while (true) {
        try {
            optimisticLockStockService.decrease(id, quantity);
            break;
        } catch (Exception e) {
            Thread.sleep(50);
        }
    }
}
```

실행되는 쿼리 (테스트 로그):

```sql
select ..., stock0_.version from stock stock0_ where stock0_.id=?           -- 일반 조회 (락 없음)
update stock set product_id=?, quantity=?, version=? where id=? and version=?   -- 읽은 버전일 때만 갱신, 버전 +1
select version as version_ from stock where id =?                          -- LockModeType.OPTIMISTIC: 커밋 전 버전 재확인
```

- 다른 트랜잭션이 먼저 갱신해 버전이 바뀌었으면 UPDATE 결과가 0건이 되고, `ObjectOptimisticLockingFailureException`이 난다.
- **재시도는 트랜잭션 바깥(Facade)에서** 한다. 같은 트랜잭션 안에서 다시 조회하면 영속성 컨텍스트에 남은 옛 엔티티(옛 버전)를 그대로 받아 계속 실패한다. 그래서 서비스(트랜잭션 1회)와 Facade(재시도 루프)를 나눴다.
- `@Lock(LockModeType.OPTIMISTIC)` 없이 `@Version`만 있어도 UPDATE의 버전 비교는 동작한다. `OPTIMISTIC`은 커밋 직전 버전 재확인 쿼리를 추가한다.
- 테스트는 `facade/OptimisticLockStockFacadeTest`로 Facade를 대상으로 한다. 실행 시간은 약 5.4초로, Pessimistic Lock(약 1.4초, 테스트 2개)보다 길다. 100건이 한 행에 몰려 충돌·재시도가 많기 때문이다.

**주의: 모든 예외를 재시도한다 (강의 코드 그대로)**

Facade가 `catch (Exception e)`로 모든 예외를 잡아 재시도한다. 재고 부족(`재고는 0개 미만이 될 수 없습니다.`)처럼 다시 시도해도 결과가 같은 예외도 **무한 반복**된다.
실무라면 `ObjectOptimisticLockingFailureException`만 재시도하고, 최대 재시도 횟수를 둬야 한다. (테스트는 `orTimeout(10초)`가 있어 무한 대기하지는 않는다.)

**장단점**

| 구분 | 내용 |
|---|---|
| 장점 | DB 락을 잡지 않으므로 **충돌이 드물면** 대기 없이 빠르다. |
| 장점 | 락 대기로 DB 커넥션을 붙잡지 않고, 데드락 걱정이 없다. |
| 장점 | DB 버전 컬럼으로 판단하므로 **서버가 여러 대여도 동작**한다. |
| 단점 | 충돌 시 **재시도 로직을 직접 구현**해야 한다(Facade, 대기 시간, 재시도할 예외·횟수). |
| 단점 | **충돌이 잦으면** 실패한 트랜잭션과 재시도가 쌓여 Pessimistic Lock보다 느려진다. 이 테스트처럼 한 행에 요청이 몰리는 경우가 그렇다. |
| 단점 | 버전 컬럼을 추가하는 등 엔티티 변경이 필요하다. |

### 4. Named Lock

_예정_ — `GET_LOCK` / `RELEASE_LOCK`, 락 획득·해제와 재고 감소의 트랜잭션 분리(`REQUIRES_NEW`)

### 5. Redis — Lettuce

_예정_ — `SETNX` spin lock, 획득 실패 시 잠시 대기 후 재시도

### 6. Redis — Redisson

_예정_ — `RLock.tryLock(대기 시간, 점유 시간)`, pub/sub 기반

## 남은 과제

- 방법별 성능(테스트 소요 시간) 비교 결과 기록
- 각 방법의 적용 기준 정리 (충돌 빈도, 서버 대수, 인프라 유무)
