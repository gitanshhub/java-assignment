# Backend Engineering Assignment

## Overview

This project is a Spring Boot microservice that uses PostgreSQL as the source of truth for posts and comments, and Redis as the stateless guardrail layer for concurrency control, cooldowns, virality scoring, and notification batching.

## Stack

- Java 17
- Spring Boot 3.3
- Spring Data JPA
- Spring Data Redis
- PostgreSQL 16
- Redis 7
- Docker Compose
- JUnit 5 / Mockito

## Implemented Requirements

- `POST /api/posts` to create a post
- `POST /api/posts/{postId}/comments` to create a comment
- `POST /api/posts/{postId}/like` to like a post
- Redis virality score updates in real time
- Horizontal bot reply cap of `100` per post
- Vertical thread depth cap of `20`
- Bot-to-human cooldown of `10` minutes
- Notification throttling with pending Redis list batching
- Scheduled sweeper every `5` minutes

## Data Model

### PostgreSQL entities

- `User`: `id`, `username`, `is_premium`
- `Bot`: `id`, `name`, `persona_description`
- `Post`: `id`, `author_id`, `author_type`, `content`, `created_at`
- `Comment`: `id`, `post_id`, `parent_comment_id`, `author_id`, `author_type`, `content`, `depth_level`, `created_at`

`author_type` was added alongside `author_id` so the service can safely distinguish whether an actor is a human or a bot.

## Guardrail Design

### Virality score

Redis key: `post:{id}:virality_score`

- Bot reply: `+1`
- Human like: `+20`
- Human comment: `+50`

### Horizontal cap

Redis key: `post:{id}:bot_count`

- Every bot reply first reserves a slot using atomic Redis `INCR`
- If the incremented value is greater than `100`, the request is rejected with `429 Too Many Requests`
- The slot is immediately released with `DECR`
- This ensures that even under concurrent request races, only the first `100` bot replies are allowed through

### Transaction integrity

- Redis guardrails are checked before the database write is considered successful
- If a database transaction fails after a bot slot is reserved, the reserved slot is released during rollback handling
- This prevents Redis counters from drifting away from persisted database state

### Vertical cap

- Nested comments compute `depthLevel = parent.depthLevel + 1`
- Any comment request producing `depthLevel > 20` is rejected

### Cooldown cap

Redis key: `cooldown:bot_{botId}:human_{humanId}`

- TTL: `10` minutes
- If the key already exists, the bot interaction is rejected with `429 Too Many Requests`

## Notification Engine

When a bot interacts with a user's post:

- If the user has not received a notification in the last `15` minutes, the app logs:
  - `Push Notification Sent to User`
- A notification cooldown key is then set in Redis
- If the user is still inside the cooldown window, the message is pushed into:
  - `user:{id}:pending_notifs`
- The user id is also registered in:
  - `pending_notif_users`

The scheduled sweeper runs every `5` minutes and:

- scans all users with pending notifications
- drains their Redis list
- logs a summarized message such as:
  - `Summarized Push Notification: Bot X and [N] others interacted with your posts.`

## Running Locally

### 1. Start PostgreSQL and Redis

```bash
docker compose up -d
```

### 2. Run the application

```bash
mvn spring-boot:run
```

### 3. Run tests

```bash
mvn test
```

## Default Configuration

- PostgreSQL URL: `jdbc:postgresql://localhost:5432/assignment_db`
- PostgreSQL username: `postgres`
- PostgreSQL password: `postgres`
- Redis host: `localhost`
- Redis port: `6379`

Supported environment overrides:

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`

## Seed Data

The app starts with sample seed data from `src/main/resources/data.sql`:

- Users:
  - `1 -> alice`
  - `2 -> bob`
- Bots:
  - `1 -> Nova`
  - `2 -> Atlas`

This makes the Postman collection usable immediately after startup.

## API Examples

### Create post

`POST /api/posts`

```json
{
  "authorType": "USER",
  "authorId": 1,
  "content": "Hello world"
}
```

### Create comment

`POST /api/posts/{postId}/comments`

```json
{
  "authorType": "BOT",
  "authorId": 1,
  "parentCommentId": null,
  "content": "Automated reply"
}
```

### Like post

`POST /api/posts/{postId}/like`

```json
{
  "userId": 1
}
```

## Testing Coverage

Automated tests cover:

- the `200` concurrent bot request spam case, asserting exactly `100` successful bot replies
- rejection when comment depth exceeds `20`
- rejection when a bot hits the same human again within the `10` minute cooldown
- notification batching behavior while the user notification cooldown is active

## Submission Notes

- The repository includes source code, `docker-compose.yml`, README, and Postman collection
- Redis is used for all counters, cooldowns, and pending notification batching
- PostgreSQL remains the source of truth for persisted content
- The application is stateless with no in-memory production counters or static state
