# AWS deployment files

Complete and validated, **not applied**. Creating these resources bills a real
account. Read [`../../docs/aws-deployment.md`](../../docs/aws-deployment.md)
first — especially section 8 (cost) and section 9 (teardown).

| File | What it is |
|------|-----------|
| `task-definition.json` | ECS Fargate task definition. `ACCOUNT_ID`, `REGION` and `VERSION` are substituted at deploy time. |
| `deploy.sh` | Idempotent build → push → register → deploy → wait. Refuses to overwrite an existing immutable tag. |
| `iam/execution-role-policy.json` | For the **ECS agent**: read this service's SSM parameters and decrypt them. |
| `iam/deploy-role-policy.json` | For **GitHub Actions**: push to one ECR repo, update one ECS service, pass exactly two roles. |
| `iam/github-oidc-trust-policy.json` | Lets Actions assume that role with **no stored AWS keys**, scoped to this repo's `main`. |

## The two ideas worth taking away

**1. A task definition revision is an immutable versioned artifact.**
Registering a new one never mutates an old one, so rolling back is pointing
the service at an earlier revision number — no rebuild, no artifact change.
The same discipline the registry enforces for tool versions, one layer up.

**2. No long-lived cloud credentials.**
Actions authenticates by OIDC: it presents a short-lived token, AWS verifies
it against the trust policy, and returns temporary credentials. There is no
`AWS_SECRET_ACCESS_KEY` in this repository to leak, rotate, or find in a git
history. The trust policy is pinned to `refs/heads/main`, so a pull request
from a fork cannot deploy.

## Deploy

```bash
AWS_REGION=ap-south-1 VERSION=0.1.42 ./deploy/aws/deploy.sh
```

## Roll back

```bash
aws ecs update-service --cluster tool-platform --service tool-registry \
  --task-definition tool-registry:41
```
