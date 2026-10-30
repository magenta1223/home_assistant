# Identity use case

`UserRegistryService`는 등록된 application 사용자 목록을 제공하는 `UserRegistry` input port이면서,
등록 여부를 판단하는 `UserAccessPolicy` 구현이기도 하다.

`UserAuthenticationService`는 이름과 비밀번호로 사용자를 등록하고 세션으로 로그인 상태를 유지하는
`UserAuthentication` input port다.

## 목록과 인가

```mermaid
sequenceDiagram
    participant Caller as Application caller
    participant Registry as UserRegistryService
    participant Store as UserStore

    Caller->>Registry: list()
    Registry->>Store: list()
    Store-->>Registry: 표시 이름이 있는 RegisteredUser 목록
    Registry-->>Caller: RegisteredUser 목록

    Caller->>Registry: isAuthorized(userId)
    Registry->>Store: isRegistered(userId)
    Store-->>Registry: true 또는 false
    Registry-->>Caller: authorization result
```

## 등록과 로그인

```mermaid
sequenceDiagram
    participant Http as HTTP route
    participant Auth as UserAuthenticationService
    participant Credentials as UserCredentialStore
    participant Hasher as PasswordHasher
    participant Sessions as UserSessionStore

    Http->>Auth: signUpFirstUser(name, password)
    Auth->>Hasher: hash(password)
    Auth->>Credentials: registerFirst(user, hash)
    Credentials-->>Auth: REGISTERED 또는 USERS_ALREADY_EXIST
    Auth-->>Http: RegisteredUser 또는 SignupClosedException

    Http->>Auth: login(name, password)
    Auth->>Credentials: findByName(name)
    Auth->>Hasher: matches(password, hash)
    Auth->>Sessions: deleteExpired(now), save(세션 id 해시, userId, 만료)
    Auth-->>Http: LoginSession 또는 InvalidCredentialsException

    Http->>Auth: resolveSession(sessionId)
    Auth->>Sessions: findUser(세션 id 해시, now)
    Sessions-->>Auth: 만료 전이면 UserId
    Auth-->>Http: UserId 또는 null
```

## 규칙

- 표시 이름의 빈 값 금지와 50자 제한은 domain의 `RegisteredUser`가 소유한다.
- HTTP 인증·열람자 목록은 기존 application userId와 등록 사용자 목록을 사용한다.
- 첫 사용자는 사용자가 0명일 때만 스스로 가입한다. 이후 사용자는 로그인한 사용자가 이름과 초기 비밀번호로 추가한다.
- 이름은 유일하다. 비밀번호는 8자 이상이며 해시만 저장한다. 세션은 90일 뒤 만료되고 id의 해시만 저장한다.
- 기계 client는 비밀번호 대신 `HTTP_MEMBER_API_KEYS_JSON`의 Bearer key를 쓴다. key의 userId는 등록된 사용자여야 하며,
  아니면 서버가 시작하지 않는다. 그래서 사용자가 0명인 DB에서는 이 설정을 비워 두고 시작한다.
