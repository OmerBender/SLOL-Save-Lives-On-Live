# Contributing

Thank you for your interest in contributing to SLOL.

## Workflow

1. Create a focused branch from `main`.
2. Make a small, reviewable change.
3. Keep documentation synchronized with the implementation.
4. Run the relevant validation checks before submitting.
5. Open a pull request with a clear summary of the changes.

## Commit Style

Recommended commit prefixes:

- `feat:` for new features
- `fix:` for bug fixes
- `docs:` for documentation changes
- `refactor:` for internal restructuring
- `test:` for test-related changes
- `chore:` for maintenance tasks

## Do Not Commit

- Model weights
- API keys
- Passwords
- Tokens
- Google Cloud credentials
- Insta360 credentials
- Android signing keys
- Recorded scenarios
- Demo videos
- Generated dashboard outputs
- Android build outputs

## Architecture Changes

Explain major architecture changes in the pull request and update the relevant files under `docs/`.

## Validation Scope

The current tests provide lightweight validation of the repository structure and documentation.

They do not test:

- YOLO inference
- WebSocket runtime behavior
- FastAPI endpoint behavior
- GPU execution
- Android integration
