# 청춘교환 백엔드

가족 연결, 관계별 책, 챕터, 미션, 감상을 제공하는 Spring 백엔드입니다.

## 개발 기준

공통 작업 절차는 [AGENTS.md](AGENTS.md)를 따릅니다. 경로·메서드·요청·정상 응답·기본 오류를 Swagger에 먼저 명세하고, API 변경과 함께 갱신합니다. 로그인 도입은 별도로 결정하며, 책 접근과 감상 작성 권한은 서버에서 확인합니다.

## 요청 예시

> 감상 제출 후 보낸 사람의 To-do 조회에도 완료 상태가 반영되는지 검증해줘.

빈 감상이나 ‘나중에 하기’는 대기 상태를 유지합니다. 감상을 저장하면 완료되고, 재요청해도 감상이 중복되지 않아야 합니다. 보낸 사람은 해당 감상을 읽을 수 있고, 다른 책의 참여자는 접근할 수 없습니다. 연동과 발표 전에는 [공통 시연 시나리오](docs/시연%20시나리오.md)를 사용합니다.

## 서버 1 실행

Java 21과 MySQL 8.0.16 이상이 필요합니다. 비어 있는 `hackathon` 데이터베이스에서 앱이 시작되면 Flyway가 확정 ERD의 서버 1 테이블 다섯 개를 생성합니다. 적용한 테이블은 `participant`, `participant_session`, `book`, `book_participant`, `book_invitation`입니다. `book`의 상태는 책의 두 자리 연결 여부로 계산합니다. 생성·만료 시각은 UTC로 저장합니다.

| 환경 변수 | 설정 |
| --- | --- |
| `DB_URL` | 필수. RDS 예시 `jdbc:mysql://<rds-endpoint>:3306/hackathon?sslMode=VERIFY_IDENTITY`. 로컬 예시 `jdbc:mysql://localhost:3306/hackathon`. |
| `DB_USERNAME`, `DB_PASSWORD` | 필수. 연결 계정과 비밀번호. |
| `INVITE_BASE_URL` | `/invite/{code}` 화면을 서비스하는 웹 주소. 기본 `http://localhost:8080`. |
| `FRONTEND_ORIGIN` | 별도 프론트 오리진이 필요할 때만 허용할 정확한 오리진을 지정합니다. 프론트 요청에서 쿠키를 포함합니다. |
| `COOKIE_SECURE` | 기본값 `true`. 로컬 HTTP 테스트에서만 `false`로 지정합니다. |

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3306/hackathon'
$env:DB_USERNAME = '로컬 DB 사용자'
$env:DB_PASSWORD = '로컬 DB 비밀번호'
$env:COOKIE_SECURE = 'false'
.\gradlew.bat bootRun
```

RDS에서 `VERIFY_IDENTITY`를 사용할 때는 [RDS 루트 CA](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html)를 애플리케이션의 신뢰 저장소에 등록합니다. Flyway 연결 계정에는 `hackathon`에서 최초 마이그레이션을 실행할 권한이 필요합니다. macOS/Linux는 `COOKIE_SECURE=false ./gradlew bootRun`을 사용합니다. 시작 후 `http://localhost:8080/swagger-ui/index.html`에서 [API 계약](src/main/resources/static/openapi/server1.yaml)을 호출할 수 있습니다. Swagger 시연은 일반 창에서 `POST /api/v1/participants` → `POST /api/v1/books` → 링크 코드 복사, 다른 브라우저 프로필에서 초대 미리보기 → 닉네임으로 수락 순으로 진행합니다. 두 브라우저 모두 `GET /api/v1/books`에서 같은 책이 `ACTIVE`인지 확인합니다.

초대 링크 유효 기간은 24시간, 기기 쿠키 유효 기간은 30일입니다. 초대 링크의 UI는 프론트에서 처리하며, API에서 받은 코드로 미리보기와 수락 요청을 보냅니다. 프론트를 별도 오리진에 올릴 때도 같은 사이트의 HTTPS 배치를 권장합니다. 쿠키를 삭제하거나 쿠키가 만료된 기기의 기존 책을 복구하는 기능은 제공하지 않습니다.

통합 테스트는 실제 MySQL 8의 별도 테스트 데이터베이스를 사용합니다. 운영용 `DB_*`와 별개로 `TEST_DB_URL`(예: `jdbc:mysql://localhost:3306/hackathon_test`), `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`를 설정한 후 `./gradlew test` 또는 `.\gradlew.bat test`를 실행합니다. 테스트는 공통 시나리오 1, 1-1, 5-1 및 제약 조건을 확인합니다.

## 다른 API에서 공통 컴포넌트 사용

`com.cheongchun.common.auth`의 `ParticipantContext`와 `BookAccessService`를 생성자로 주입하고, 요청 안에서 아래 순서로 호출합니다. 쿠키가 없거나 만료·폐기됐으면 `401`, 책이 없거나 참여하지 않은 책이면 같은 `404`를 반환합니다. 요청 본문의 ID 대신 여기서 얻은 `participantId`로 작성자나 감상 제출자를 확인합니다.

```java
long participantId = participantContext.currentParticipantId();
BookParticipant membership = bookAccessService.requireParticipant(bookId, participantId);
```

이후 작성자·미션 수행자 등 기능별 권한이 부족하면 `ApiException.forbidden(message)`를 던집니다. `com.cheongchun.common.api`의 `ApiResponse.success(data)`를 성공 응답에 쓰고, 생성은 HTTP `201`, 조회 및 변경은 `200`을 사용합니다. 잘못된 입력은 `ApiException.invalidRequest(message)`로 처리합니다. 전역 예외 처리기가 실패 응답을 만들어주며, 프론트는 실패 코드를 `error.code`에서 읽습니다. 사용되거나 만료된 초대 등 상태 충돌은 기존처럼 `409`입니다.
