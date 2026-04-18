# AWS deployment (Terraform + GitHub Actions)

This stack provisions:

- **VPC** with public subnets (ALB + Fargate with public IPs) and private subnets (**ElastiCache Redis**).
- **DynamoDB** table `cw3-ElectionRaces` (partition key `raceId`, GSI `state-index` on `state`).
- **ECR** repository for the Spring Boot image.
- **ECS Fargate** service behind an **Application Load Balancer** (HTTP on port 80 by default, or HTTPS on 443 if `certificate_arn` is set).
- **S3** + **CloudFront** for the Vite production build.

Application code changes (already in the repo) support AWS:

- `AWS_USE_LOCALSTACK=false`, **IAM task role** for DynamoDB (no static `test` credentials).
- **`CORS_ALLOWED_ORIGINS`** should include your CloudFront URL `https://<distribution>.cloudfront.net` (Terraform sets this automatically unless you override `cors_allowed_origins`).
- **`DYNAMODB_TABLE_NAME`** matches the Terraform table name.

## Prerequisites

- AWS CLI and Terraform `>= 1.5` installed.
- An AWS account; **billing alarms** recommended.
- **GitHub OIDC** IAM role allowing `ecr:*` push, `ecs:UpdateService`, `s3:PutObject`, `cloudfront:CreateInvalidation` (see below).

## 1. Configure variables

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars
# Edit: frontend_bucket_name (globally unique), backend_image (after first ECR push — see step 3)
```

## 2. First-time Terraform apply (infrastructure only)

```bash
terraform init
terraform apply
```

On the first run, **ECS tasks may fail** until a real image exists in ECR — that is expected.

## 3. Push the backend image

From the repo root (after `terraform apply`):

```bash
AWS_REGION=us-east-1
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
REGISTRY="$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"

aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY"

docker build -t "$REGISTRY/cw3-backend:latest" ./backend
docker push "$REGISTRY/cw3-backend:latest"
```

Update `terraform.tfvars`:

```hcl
backend_image = "<ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com/cw3-backend:latest"
```

Then:

```bash
terraform apply
```

This refreshes the ECS task definition with the image and correct **CORS** / **DynamoDB** env vars.

## 4. Add API keys to ECS

The task definition only sets core env vars. In the **AWS Console → ECS → Task definition → Create new revision**, add secrets (e.g. from **Secrets Manager**) or plain env for:

`GUARDIAN_API_KEY`, `OPEN_FEC_API_KEY`, Polymarket-related vars, etc., matching [`docker-compose.yml`](../docker-compose.yml) / [`application.yml`](../backend/src/main/resources/application.yml).

Redeploy the service after updating the task definition.

## 5. Build and upload the frontend

Point the UI at the **ALB** URL from Terraform output (`api_base_url_http` or `api_base_url_https`):

```bash
cd state-of-the-race
export VITE_API_BASE_URL="http://cw3-api-xxxx.us-east-1.elb.amazonaws.com"   # use your output
npm ci && npm run build

aws s3 sync dist "s3://YOUR_FRONTEND_BUCKET" --delete
aws cloudfront create-invalidation --distribution-id YOUR_DIST_ID --paths "/*"
```

Use outputs: `frontend_bucket_id`, `cloudfront_distribution_id`, `api_base_url_http`.

**HTTPS note:** Browsers may block mixed content if the SPA is on **HTTPS (CloudFront)** and the API is **HTTP (ALB)**. Prefer attaching an **ACM certificate** to the ALB (`certificate_arn` in `terraform.tfvars`) and use `https://` for `VITE_API_BASE_URL`.

## 6. GitHub Actions OIDC

Create an IAM role with a **trust policy** like:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com" },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:YOUR_ORG/YOUR_REPO:ref:refs/heads/main"
        }
      }
    }
  ]
}
```

Attach policies allowing ECR push, ECS `UpdateService`, S3 sync to the frontend bucket, and CloudFront invalidation.

Repository secrets (see [`.github/workflows/deploy-aws.yml`](../.github/workflows/deploy-aws.yml)):

| Secret | Purpose |
|--------|---------|
| `AWS_ROLE_TO_ASSUME` | IAM role ARN for OIDC |
| `VITE_API_BASE_URL` | Public API base URL (ALB) |
| `S3_FRONTEND_BUCKET` | S3 bucket name |
| `CLOUDFRONT_DIST_ID` | Distribution ID |
| `ECR_REPOSITORY` | Optional; default `cw3-backend` |
| `ECS_CLUSTER_NAME` / `ECS_SERVICE_NAME` | Optional; default `cw3-cluster` / `cw3-backend` |

## Remote state (recommended for teams)

Add an S3 backend and DynamoDB lock table to `versions.tf` — not included by default.

## Cost awareness

ElastiCache, ALB, Fargate, and data transfer can exceed free tier. Use **AWS Budgets** and review **PriceClass_100** on CloudFront (already set).
