# 프로젝트 설명 — Redis (Redisson)

> [README](../README.md)로 돌아가기

## 요구사항

- 재고는 **0 미만으로 내려갈 수 없다.** (부족하면 예외)
- 재고 100개에 1개씩 100건을 동시에 요청하면 재고는 **0**이 되어야 한다.

## 기술 스펙

| 구분 | 사용 기술 |
|---|---|
| Language | Java 11 |
| Framework | Spring Boot 2.7 (Spring Web, Spring Data JPA, Spring Data Redis) |
| Database | MySQL (Docker) |
| Lock | Redis — Redisson (`redisson-spring-boot-starter:3.18.0`) |
| Build | Gradle |
| 기타 | Lombok |

## 구조

```
src
├── main/java/com/example/stock
│   ├── config       # RedisConfig — RedisTemplate이 Lettuce로 연결하도록 연결 팩토리 등록
│   ├── domain       # Stock — decrease()에서 0 미만 검증
│   ├── repository   # 이전 단계 리포지토리
│   ├── service      # RedisLockStockService — synchronized 없이 @Transactional (이전 단계 서비스도 포함)
│   └── facade       # RedissonLockStockFacade — tryLock(10초 대기, 1초 점유) → 재고 감소 → 락 해제
└── test/java/com/example/stock
    ├── facade       # RedissonLockStockFacadeTest (이전 단계 테스트도 포함)
    └── service      # 이전 단계 테스트
```

## 실행 방법

```bash
# 1. MySQL
docker run -d -p 3306:3306 -e MYSQL_ROOT_PASSWORD=1234 --name mysql mysql
docker exec -it mysql mysql -uroot -p1234 -e "CREATE DATABASE stock_example;"

# 2. Redis (WSL)
sudo apt install redis-server        # 최초 1회
sudo systemctl start redis-server
redis-cli ping                       # PONG 이면 정상

# 3. 설정 파일 (application.yaml은 Git 관리 대상이 아니다)
cp src/main/resources/application.yaml.example src/main/resources/application.yaml
# url: jdbc:mysql://127.0.0.1:3306/stock_example, username, password 입력

# 4. 테스트
./gradlew test --tests 'com.example.stock.facade.RedissonLockStockFacadeTest'
```

- Redis 접속 정보는 기본값(`localhost:6379`)을 쓰므로 설정 파일에 따로 적지 않는다.
- 락 명령을 직접 보려면 테스트 중 다른 터미널에서 `redis-cli monitor`를 실행한다.

## 테스트 (`RedissonLockStockFacadeTest`)

| 테스트 | 검증 내용 |
|---|---|
| `동시에_100개의_요청` | Facade로 100건 동시 감소 → 0 |

- 스레드 32개짜리 `ExecutorService` 위에서 `CompletableFuture.runAsync`로 100건을 보내고, `allOf(...).orTimeout(10초).join()`으로 기다린다.
- 한 번 실행에서 획득 시도 `EVAL`은 244번(성공 100 + 재시도 144), 해제 `EVAL`·`publish`는 100번, `SUBSCRIBE`는 1번 나갔다. 소요 시간은 약 1.3초.
  같은 조건의 Lettuce spin lock은 `SET` 875번, 약 4.7초였다.
- `@BeforeEach`에서 저장한 엔티티의 `id`(`stockId`)로 조회한다. `@AfterEach`의 `deleteAll()`은 AUTO_INCREMENT를 초기화하지 않으므로 `1L`로 고정하면 찾지 못할 수 있다.
