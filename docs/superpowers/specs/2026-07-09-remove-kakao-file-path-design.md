# Kakao Analyze Request Cleanup Design

## Goal

Remove server-side file-path reading from `POST /api/kakao/import/analyze` and make the remaining request fields describe their actual purpose.

## HTTP Contract

The endpoint will accept:

```json
{
  "sourceName": "family-chat.txt",
  "text": "Kakao export contents"
}
```

Both fields are required and must be non-blank.

The following legacy fields will no longer be supported:

- `filePath`
- `fileName`

Because the shared JSON configuration ignores unknown fields, a request containing only a legacy field will fail normal required-field validation with HTTP 400 and will not invoke topic analysis.

## Implementation

- Replace `fileName` with `sourceName` in the route-private request DTO.
- Remove `filePath` and all `Files`/`Path` usage from the route.
- Pass the validated `sourceName` and `text` to `TopicAnalysisRequest`.
- Keep the internal topic-analysis use case and Slack workflow unchanged.
- Update `AGENTS.md` to describe text-content input rather than server-side file input.

## Error Handling

- Blank or missing `sourceName`: HTTP 400 with `sourceName is required`.
- Blank or missing `text`: HTTP 400 with `text is required`.
- Unknown legacy fields alone must not cause filesystem access or analysis execution.

## Testing

- Update the successful route test to send `sourceName` and `text`.
- Add a regression test proving a `filePath`-only request returns HTTP 400 and does not invoke analysis.
- Add a compatibility-breaking contract test proving `fileName` no longer substitutes for `sourceName`.
- Run the focused app route tests, then the full Gradle build.

## Non-goals

- Removing the HTTP endpoint.
- Changing Slack file ingestion.
- Changing topic-analysis persistence or fingerprint behavior.
- Adding multipart upload support.
