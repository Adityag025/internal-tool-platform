#!/usr/bin/env bash
#
# Build, push and deploy the Tool Registry to ECS Fargate.
#
#   AWS_REGION=ap-south-1 VERSION=0.1.42 ./deploy/aws/deploy.sh
#
# Idempotent: safe to re-run. Creates what is missing, updates what exists.
#
# THIS SCRIPT CREATES BILLABLE AWS RESOURCES. Read docs/aws-deployment.md
# section 8 for the cost breakdown before running it, and section 9 for how to
# tear everything down again.
set -euo pipefail

AWS_REGION="${AWS_REGION:?set AWS_REGION, e.g. ap-south-1}"
VERSION="${VERSION:?set VERSION, e.g. 0.1.42 - never 'latest'}"
CLUSTER="${CLUSTER:-tool-platform}"
SERVICE="${SERVICE:-tool-registry}"
REPO="${REPO:-tool-registry}"
FAMILY="${FAMILY:-tool-registry}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
IMAGE="${REGISTRY}/${REPO}:${VERSION}"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

say "Target"
echo "  account : $ACCOUNT_ID"
echo "  region  : $AWS_REGION"
echo "  image   : $IMAGE"
echo "  service : $CLUSTER/$SERVICE"

# --------------------------------------------------------------- 1. registry
say "1. ECR repository"
if aws ecr describe-repositories --repository-names "$REPO" --region "$AWS_REGION" >/dev/null 2>&1; then
  echo "  exists"
else
  aws ecr create-repository \
    --repository-name "$REPO" \
    --region "$AWS_REGION" \
    --image-tag-mutability IMMUTABLE \
    --image-scanning-configuration scanOnPush=true \
    --query 'repository.repositoryUri' --output text
  # IMMUTABLE is the point: ECR itself then refuses to overwrite an existing
  # tag, exactly as the registry refuses to overwrite a published version.
  # A deployed tag can never come to mean different bytes than the tag you tested.
fi

# Refuse to redeploy over an existing tag - the same immutability rule, enforced
# before we waste time building.
if aws ecr describe-images --repository-name "$REPO" --image-ids imageTag="$VERSION" \
     --region "$AWS_REGION" >/dev/null 2>&1; then
  echo "  ERROR: $REPO:$VERSION already exists and tags are immutable."
  echo "         Deploy that tag, or build a new version. Never overwrite one."
  exit 1
fi

# ----------------------------------------------------------------- 2. build
say "2. Build the image"
# --provenance=false keeps the manifest a plain image rather than an index;
# some older ECS/ECR paths choke on an OCI index.
docker buildx build \
  --platform linux/amd64 \
  --provenance=false \
  -f "$ROOT/docker/Dockerfile" \
  -t "$IMAGE" \
  --load \
  "$ROOT/backend"

# ------------------------------------------------------------------ 3. push
say "3. Push to ECR"
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$REGISTRY"
docker push "$IMAGE"

DIGEST="$(aws ecr describe-images --repository-name "$REPO" --image-ids imageTag="$VERSION" \
          --region "$AWS_REGION" --query 'imageDetails[0].imageDigest' --output text)"
echo "  digest: $DIGEST"

# -------------------------------------------------------- 4. task definition
say "4. Register the task definition"
RENDERED="$(mktemp)"
sed -e "s|ACCOUNT_ID|${ACCOUNT_ID}|g" \
    -e "s|REGION|${AWS_REGION}|g" \
    -e "s|:VERSION|:${VERSION}|g" \
    "$ROOT/deploy/aws/task-definition.json" > "$RENDERED"

REVISION="$(aws ecs register-task-definition \
  --cli-input-json "file://$RENDERED" \
  --region "$AWS_REGION" \
  --query 'taskDefinition.revision' --output text)"
rm -f "$RENDERED"
echo "  registered ${FAMILY}:${REVISION}"
# Every register creates a NEW immutable revision. Rolling back is pointing the
# service at an earlier revision number - no rebuild, exactly like moving a
# client's pin back to an earlier tool version.

# ----------------------------------------------------------------- 5. deploy
say "5. Update the service"
if aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
     --region "$AWS_REGION" --query 'services[0].status' --output text 2>/dev/null | grep -q ACTIVE; then
  aws ecs update-service \
    --cluster "$CLUSTER" --service "$SERVICE" \
    --task-definition "${FAMILY}:${REVISION}" \
    --region "$AWS_REGION" >/dev/null
  echo "  rolling update started"
else
  echo "  service does not exist yet."
  echo "  Create it once with the command in docs/aws-deployment.md section 5."
  exit 1
fi

say "6. Wait for the rollout"
# Blocks until the service is steady: the new tasks passed their health checks
# and the old ones are drained. Without this the script "succeeds" while the
# new version is crash-looping.
aws ecs wait services-stable --cluster "$CLUSTER" --services "$SERVICE" --region "$AWS_REGION"

say "Deployed ${FAMILY}:${REVISION}  (${VERSION})"
