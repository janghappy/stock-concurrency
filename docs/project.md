# 프로젝트 설명 — Optimistic Lock

> [README](../README.md)로 돌아가기

## 요구사항

- 재고는 **0 미만으로 내려갈 수 없다.** (부족하면 예외)
- 재고 100개에 1개씩 100건을 동시에 요청하면 재고는 **0**이 되어야 한다.

## 기술 스펙

| 구분 | 사용 기술 |
|---|---|
| Language | Java 11 |
| Framework | Spring Boot 2.7 (Spring Web, Spring Data JPA) |
| Database | MySQL (Docker) |
| Build | Gradle |
| 기타 | Lombok |

## 구조

```
src
├── main/java/com/example/stock
│   ├── domain       # Stock — @Version, decrease()에서 0 미만 검증
│   ├── repository   # StockRepository — findByIdWithOptimisticLock (OPTIMISTIC)
│   ├── service      # OptimisticLockStockService (이전 단계: StockService, PessimisticLockStockService)
│   └── facade       # OptimisticLockStockFacade — 충돌 시 50ms 대기 후 재시도
└── test/java/com/example/stock
    ├── facade       # OptimisticLockStockFacadeTest
    └── service      # 이전 단계 테스트
```

## 실행 방법

```bash
# 1. MySQL
docker run -d -p 3306:3306 -e MYSQL_ROOT_PASSWORD=1234 --name mysql mysql
docker exec -it mysql mysql -uroot -p1234 -e "CREATE DATABASE stock_example;"

# 2. 설정 파일 (application.yaml은 Git 관리 대상이 아니다)
cp src/main/resources/application.yaml.example src/main/resources/application.yaml
# url: jdbc:mysql://127.0.0.1:3306/stock_example, username, password 입력

# 3. 테스트
./gradlew test --tests 'com.example.stock.facade.OptimisticLockStockFacadeTest'
```

## 테스트 (`OptimisticLockStockFacadeTest`)

| 테스트 | 검증 내용 |
|---|---|
| `동시에_100개의_요청` | Facade로 100건 동시 감소 → 0 (약 5.4초) |

- 스레드 32개짜리 `ExecutorService` 위에서 `CompletableFuture.runAsync`로 100건을 보내고, `allOf(...).orTimeout(10초).join()`으로 기다린다.
- Facade의 `decrease`가 `InterruptedException`을 던지므로 람다 안에서 `RuntimeException`으로 감싼다.
- `@BeforeEach`에서 저장한 엔티티의 `id`(`stockId`)로 조회한다. `@AfterEach`의 `deleteAll()`은 AUTO_INCREMENT를 초기화하지 않으므로 `1L`로 고정하면 찾지 못할 수 있다.
