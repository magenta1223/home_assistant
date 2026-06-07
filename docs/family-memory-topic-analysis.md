# Family Memory Topic Analysis

This project treats imported messages as source records first. Durable family memories are not created directly from an LLM response.

The current flow is:

1. Import source records, such as Kakao export messages.
2. Render the imported records as a source document with stable record ids like `r1`, `r2`, and `r3`.
3. Ask the LLM to produce topic candidates.
4. Require every topic to include evidence record ids and at least one evidence-backed claim.
5. Persist topics, claims, and claim evidence as pending review data.
6. Approve or reject pending candidates before turning them into durable memory.

## Topic Shape

A topic candidate groups related source records for family or household recall. It keeps the existing fields:

- `title`
- `summary`
- `memoryTypes`
- `domains`
- `evidenceRecordIds`

It also includes `claims`. Claims are the smaller evidence-backed statements that can become memory facts, events, preferences, commitments, or decisions.

## Claim Shape

Each claim stores:

- `text`: the atomic statement.
- `subject`: the person, place, object, family member, or household entity the claim is about.
- `memoryType`: one of `FACT`, `EVENT`, `COMMITMENT`, `PREFERENCE`, or `DECISION`.
- `certainty`: one of `OBSERVED`, `SAID`, `INFERRED`, or `UNCERTAIN`.
- `evidenceRecordIds`: source record ids supporting the claim.

Claim evidence is persisted separately from topic evidence so approval and future retrieval can trace a specific statement back to the imported records that support it.

## Output Contract

The LLM output contract is generated from `@Serializable` DTOs with `kotlinx-schema`.

`TopicAnalysisOutputSchema` generates JSON Schema from the topic analysis output DTO and passes it to OpenRouter as `response_format`. The prompt remains a short task description; required fields, nested shape, and enum values are enforced by the generated schema and by Kotlin DTO parsing.

If a model ignores the schema or returns invalid values, `TopicAnalysisService` fails with `TopicAnalysisException`. It does not silently fall back or create partial memory candidates.
