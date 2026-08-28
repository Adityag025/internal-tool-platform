# AWS deployment

> **Nothing in this document has been provisioned.** There are no AWS
> credentials on this machine, and creating these resources bills a real
> account. The files under `deploy/aws/` are complete and valid — they have
> been JSON- and shell-validated — but they have not been applied. Section 11
> says exactly what that means for how you describe this work.

## 1. Choosing the compute

Four ways to run a container on AWS, in increasing order of concepts:

| Option | You manage | Deploys | Cost (this workload) | Concepts to learn |
|--------|-----------|---------|---------------------:|-------------------|
| **EC2 + docker compose** | the whole VM: OS, patching, Docker | you, by hand or by script | **$0** on free tier | ~3 |
| **App Runner** | nothing | automatic from ECR | ~$25/mo, always-on | ~2 |
| **ECS Fargate** | nothing | rolling, managed | ~$9/mo + $18 if ALB | ~8 |
| **EKS (Kubernetes)** | workloads | rolling, managed | ~$73/mo control plane alone | ~30 |

### The recommendation, and it is two answers

**To see it running on AWS today: EC2.** One `t4g.micro`, free-tier eligible
for 12 months on a new account, running the *exact* `docker compose` stack that
already works locally. Path A below is about twenty minutes and costs nothing.
It is the simplest thing that could possibly work, and simplicity is a real
virtue rather than a consolation prize.

**To learn what job descriptions actually ask for: ECS Fargate.** Path B, and
what `deploy/aws/` implements. It is worth the extra concepts for one reason
that fits this project exactly:

> **A task definition revision is an immutable, versioned artifact.**
> Registering a new one never mutates the old one. Rolling back is pointing
> the service at an earlier revision number — no rebuild, no redeploy of
> anything. That is the same discipline the registry enforces for tool
> versions, one layer up the stack.

EKS is not on the list of things to do here. Kubernetes is the right answer at
a scale this project does not have, and $73/month before a single container
runs is a poor trade for learning.

## 2. Architecture (Path B)

```
   git tag v0.1.42
        │
        ▼
  GitHub Actions ──OIDC──► AWS  (temporary credentials, no stored keys)
        │
        ├──► build image ──► ECR  (immutable tags, scan on push)
        │
        └──► register task definition (new immutable revision)
                    │
                    ▼
              ECS Fargate service ──► CloudWatch Logs
                    │
                    ├──► SSM Parameter Store   (DB credentials)
                    └──► RDS PostgreSQL        (section 7)
```

## 3. Prerequisites

```bash
aws --version              # v2
aws sts get-caller-identity
docker buildx version
```

**Set a billing alarm before anything else.** This is not optional advice:

```bash
aws budgets create-budget --account-id "$(aws sts get-caller-identity --query Account --output text)" \
  --budget '{"BudgetName":"learning-cap","BudgetLimit":{"Amount":"10","Unit":"USD"},
             "TimeUnit":"MONTHLY","BudgetType":"COST"}'
```

The most common way a learning project goes wrong on AWS is not a technical
failure. It is a resource left running for three months.

---

## Path A — EC2, the simple one

One VM running the compose stack you already have.

```bash
# 1. a t4g.micro (ARM, free-tier eligible), Amazon Linux 2023
aws ec2 run-instances \
  --image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
  --instance-type t4g.micro \
  --key-name YOUR_KEY \
  --security-group-ids sg-XXXX \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=tool-platform}]'

# 2. on the instance
sudo dnf install -y docker git && sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user && exec newgrp docker
git clone https://github.com/Adityag025/internal-tool-platform && cd internal-tool-platform
cp .env.example .env && $EDITOR .env          # set a real POSTGRES_PASSWORD
docker compose -f docker/docker-compose.yml up -d --wait
```

Open the security group for 8081 **from your IP only**, never `0.0.0.0/0` —
this service has no authentication until Phase 9.

**What Path A honestly gives up:** no rolling deploys (there is a gap where
nothing is running), no health-check-driven replacement, the VM is a snowflake
you patch yourself, and a `docker compose up` on a live box is a deploy with
no audit trail. Fine for learning. Not fine for anything with users.

---

## Path B — ECS Fargate

### 4. One-time setup

**4.1 ECR repository** — created by `deploy.sh`, but note the two flags:

```bash
aws ecr create-repository --repository-name tool-registry \
  --image-tag-mutability IMMUTABLE \
  --image-scanning-configuration scanOnPush=true
```

`IMMUTABLE` makes ECR itself refuse to overwrite an existing tag. Without it,
`:0.1.42` can be made to mean different bytes tomorrow — and then a rollback
to `0.1.42` does not restore what you tested. Same rule as the registry, same
reason.

**4.2 Secrets** — the values never enter git or a task definition:

```bash
aws ssm put-parameter --name /tool-registry/db-url      --type String       --value 'jdbc:postgresql://...'
aws ssm put-parameter --name /tool-registry/db-username --type String       --value 'toolplatform'
aws ssm put-parameter --name /tool-registry/db-password --type SecureString --value "$(openssl rand -base64 24)"
```

`SecureString` is KMS-encrypted at rest, which is why the execution role also
needs `kms:Decrypt` (`deploy/aws/iam/execution-role-policy.json`).

**4.3 Two IAM roles.** Conflating them is the most common ECS mistake:

| Role | Belongs to | Needs |
|------|-----------|-------|
| **Execution role** | the ECS *agent* | pull from ECR, write logs, read your secrets |
| **Task role** | your *application* | whatever AWS APIs the app itself calls |

This application calls no AWS APIs, so its task role is empty. That is the
correct answer, not a gap — least privilege means an empty policy when nothing
is needed.

**4.4 Cluster**

```bash
aws ecs create-cluster --cluster-name tool-platform
```

### 5. Create the service (once)

```bash
aws ecs create-service \
  --cluster tool-platform \
  --service-name tool-registry \
  --task-definition tool-registry:1 \
  --desired-count 1 \
  --launch-type FARGATE \
  --network-configuration 'awsvpcConfiguration={
      subnets=[subnet-AAA,subnet-BBB],
      securityGroups=[sg-XXXX],
      assignPublicIp=ENABLED}' \
  --health-check-grace-period-seconds 120
```

Three things that bite:

- **`assignPublicIp=ENABLED`** in a public subnet, or the task cannot reach
  ECR to pull its own image and dies with an opaque `CannotPullContainerError`.
  The alternative is a private subnet plus a NAT gateway — which is ~$32/month
  and the single most common surprise on an AWS bill.
- **`--health-check-grace-period-seconds`** must exceed JVM start-up plus
  Flyway migrations, or ECS kills the task before it finishes starting and you
  get a crash loop that looks exactly like an application bug.
- **Two subnets in different availability zones**, even at `desired-count 1`.
  It costs nothing and means an AZ outage is survivable.

### 6. Deploy

```bash
AWS_REGION=ap-south-1 VERSION=0.1.42 ./deploy/aws/deploy.sh
```

Or by tag, through the pipeline: `git tag v0.1.42 && git push --tags`.

**Rollback is one command and no rebuild:**

```bash
aws ecs update-service --cluster tool-platform --service tool-registry \
  --task-definition tool-registry:41
```

Revision 41 still exists, still points at an immutable ECR digest, and is
still exactly what was tested. This is the same mechanism as re-pinning a
client to an earlier tool version — a pointer moves, nothing is rebuilt.

---

## 7. The two things this deployment gets wrong on purpose

Both are honest simplifications for a learning deployment, and naming them is
more useful than hiding them.

### 7.1 Artifact storage is ephemeral

The task definition mounts the filesystem artifact store on a task-scoped
volume. **A Fargate task's storage dies with the task**, so every uploaded
artifact vanishes on the next deploy.

That is acceptable for a demo and unacceptable otherwise. Three real fixes,
in order of preference:

1. **Point at Artifactory** — `ARTIFACT_STORE=artifactory`. The adapter exists
   (Phase 3); this is a configuration change, not code. This is what the
   port/adapter split was for.
2. **An S3 adapter** — a new `ArtifactStore` implementation, ~80 lines,
   plus `s3:GetObject`/`PutObject` on the *task* role. Cheap, durable,
   and the natural AWS-native answer.
3. **EFS** — a network filesystem mounted into the task. Works with zero code
   change, but it is slower and more expensive than S3 for write-once,
   read-many blobs, which is exactly what artifacts are.

### 7.2 PostgreSQL

Start with the database wherever it already is — even on the EC2 box or your
laptop over a tunnel — because it keeps the first deployment to one moving
part. Then move it:

```bash
aws rds create-db-instance \
  --db-instance-identifier tool-platform \
  --db-instance-class db.t4g.micro \
  --engine postgres --engine-version 16 \
  --allocated-storage 20 \
  --master-username toolplatform \
  --manage-master-user-password \
  --no-publicly-accessible \
  --backup-retention-period 7
```

- `--manage-master-user-password` puts the password in Secrets Manager and
  rotates it. Nobody ever types it, which means nobody can leak it.
- `--no-publicly-accessible` plus a security group that only admits the task's
  security group. A database reachable from the internet is a database that
  will be found — Shodan indexes open PostgreSQL ports continuously.
- `--backup-retention-period 7` because the default of 1 is not a backup
  policy.

Then update `/tool-registry/db-url` and redeploy. **The application code does
not change at all** — that is the entire point of externalised configuration,
and it is the same code that ran against `localhost:5433` in Phase 1.

Flyway migrates the schema on first start, exactly as it does locally.

## 8. What it costs

| Resource | Config | Monthly |
|----------|--------|--------:|
| Fargate task | 0.25 vCPU / 0.5 GB, 24/7 | **~$9** |
| Application Load Balancer | 1 ALB | **~$18** |
| NAT Gateway | if private subnets | **~$32** |
| RDS | db.t4g.micro + 20 GB | **~$15** (free tier: $0 for 12 months) |
| ECR | ~1 GB of images | ~$0.10 |
| CloudWatch Logs | ~1 GB | ~$0.50 |

**A learning deployment should be about $9/month**: one Fargate task in a
public subnet, reached on its own public IP. Skip the ALB (that is the
expensive line) and skip the NAT gateway (that is the *surprising* line).

Add the ALB when you actually need what it provides — TLS termination, a
stable DNS name, health-check-driven routing, more than one task.

## 9. Tearing it down

Run this the moment you stop using it. In this order:

```bash
aws ecs update-service --cluster tool-platform --service tool-registry --desired-count 0
aws ecs delete-service  --cluster tool-platform --service tool-registry --force
aws ecs delete-cluster  --cluster tool-platform
aws rds delete-db-instance --db-instance-identifier tool-platform --skip-final-snapshot
aws ecr delete-repository --repository-name tool-registry --force
aws logs delete-log-group --log-group-name /ecs/tool-registry
# and the expensive ones people forget:
aws ec2 describe-nat-gateways --query 'NatGateways[].NatGatewayId'
aws elbv2 describe-load-balancers --query 'LoadBalancers[].LoadBalancerArn'
```

NAT gateways and load balancers bill by the hour whether or not anything is
using them, and neither is deleted by removing the service in front of it.

## 10. The dashboard on Vercel

The Spring Boot service **cannot** run on Vercel — Vercel runs serverless
functions and static sites, not long-lived JVM processes, and it hosts neither
PostgreSQL nor Artifactory. But the Phase 7 dashboard is static files, which
is exactly what Vercel is for:

```bash
cd frontend && npm run build
vercel deploy --prod
```

`frontend/vercel.json` is committed. Two things to get right:

1. Point it at the API: `https://your-app.vercel.app/?api=https://your-ecs-host:8081`
2. Add that Vercel origin to `CORS_ALLOWED_ORIGINS` in the task definition, or
   the browser will refuse to read the responses.

If you want the *backend* on a free-tier platform-as-a-service instead of AWS,
**Render** or **Fly.io** both run the committed Dockerfile directly and
include a managed PostgreSQL. That is a genuinely simpler path to a public URL
than ECS, and worth doing if the goal is a link on a résumé rather than
learning ECS itself.

## 11. What has and has not been done

Be precise about this in an interview; the distinction is easy to state and
impossible to fake.

**Built and verified:** the multi-stage image, the ECS task definition, IAM
policies scoped to single resources, the OIDC trust policy, an idempotent
deploy script that refuses to overwrite an immutable tag, and a CD workflow
that waits for the rollout to stabilise before reporting success.

**Not done:** none of it has been applied to an AWS account. No cluster, no
service, no RDS instance exists.

The honest sentence is: *"I wrote the ECS deployment — task definition, IAM
roles, an OIDC-authenticated pipeline — and I can walk you through every
decision in it. I did not provision it, because it bills a real account."*
That is a stronger answer than a vague claim of having "deployed to AWS", and
it survives the follow-up question.
