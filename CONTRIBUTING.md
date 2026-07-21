# Contributing

Thank you for contributing to SLOL.

## Workflow

1. Create a focused branch from `main`.
2. Make a small, reviewable change.
3. Keep documentation synchronized with behavior.
4. Run relevant checks before submitting.
5. Open a pull request with a clear description.

## Commit Style

Recommended prefixes:

* `feat:` for new features
* `fix:` for bug fixes
* `docs:` for documentation
* `refactor:` for internal restructuring
* `test:` for tests
* `chore:` for maintenance

## Do Not Commit

* Model weights
* API keys
* Passwords
* Tokens
* Google Cloud credentials
* Insta360 credentials
* Android signing keys
* Recorded scenarios
* Demo videos
* Generated dashboard outputs
* Android build outputs

## Architecture Changes

Explain major architecture changes in the pull request and update the relevant files under `docs/`.

## Validation Scope

The current tests are lightweight repository structure and documentation validation checks. They do not test YOLO inference, WebSocket runtime behavior, FastAPI endpoint behavior, GPU execution, or Android integration.
