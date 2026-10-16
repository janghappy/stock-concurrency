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

현재는 Spring Boot 기본 골격(`StockApplication`)만 있다. 아래는 강의 소스 기준으로 만들어 갈 구조이며, 구현하면서 갱신한다.

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

### 2. Redis 실행 (6·7단계부터)

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

> 테스트는 실제 MySQL(·Redis)에 붙는 `@SpringBootTest`이므로 1~3단계가 먼저 되어 있어야 한다.

## 동시성 테스트 방식

모든 방법을 같은 조건으로 검증한다.

- `@BeforeEach`: 재고 100개짜리 상품 저장 / `@AfterEach`: 전체 삭제
- `ExecutorService`(스레드 32개)로 재고 1개 감소 요청 100건을 동시에 보내고, `CountDownLatch(100)`로 모두 끝날 때까지 기다린다.
- 남은 재고가 **0**인지 확인한다. (동시성 제어가 없으면 0보다 큰 값이 남아 실패한다.)

## 구현 상세

각 단계를 구현하면서 아래에 정리한다. 강의 소스와 다르게 구현한 부분은 차이점과 이유를 함께 적는다.

### 1. 재고 감소 로직과 Race Condition

_예정_

### 2. `synchronized`

_예정_

- 확인할 점: `@Transactional`과 함께 쓰면 프록시가 커밋하기 전에 메서드(락)가 끝나, 다른 스레드가 커밋 전 값을 읽는다.
- 확인할 점: 한 프로세스 안에서만 보장되므로 서버가 여러 대면 해결되지 않는다.

### 3. Pessimistic Lock

_예정_ — `@Lock(LockModeType.PESSIMISTIC_WRITE)` 조회 (`SELECT ... FOR UPDATE`)

### 4. Optimistic Lock

_예정_ — `@Version` + `@Lock(LockModeType.OPTIMISTIC)`, 충돌 시 Facade에서 대기 후 재시도

### 5. Named Lock

_예정_ — `GET_LOCK` / `RELEASE_LOCK`, 락 획득·해제와 재고 감소의 트랜잭션 분리(`REQUIRES_NEW`)

### 6. Redis — Lettuce

_예정_ — `SETNX` spin lock, 획득 실패 시 잠시 대기 후 재시도

### 7. Redis — Redisson

_예정_ — `RLock.tryLock(대기 시간, 점유 시간)`, pub/sub 기반

## 남은 과제

- 방법별 성능(테스트 소요 시간) 비교 결과 기록
- 각 방법의 적용 기준 정리 (충돌 빈도, 서버 대수, 인프라 유무)
