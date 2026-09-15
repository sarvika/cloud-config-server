# Running it locally

1. **Start the backing services:**
   ```
   docker compose up -d
   ```
   This starts OpenBao (dev mode, root token `root`, host port `18200`) and [Moto](https://github.com/getmoto/moto) (open-source AWS mock, host port `5000` — used instead of LocalStack because LocalStack's `latest` image now requires a paid auth token for Secrets Manager).

   > If port `18200` or `5000` is already in use on your machine, change the mapping in `docker-compose.yml` and the matching `application.yml` values.

2. **Seed OpenBao** (KV v2, path convention `secret/{application},{profile}`):
   ```
   curl --header "X-Vault-Token: root" --request POST \
     --data '{"data":{"demo.source":"vault","demo.greeting":"hello-from-openbao","demo.db.password":"openbao-real-secret-p@ss"}}' \
     "http://localhost:18200/v1/secret/data/demo-app,staging"
   ```

3. **Seed Moto (AWS Secrets Manager)** — no real AWS credentials or SigV4 signing needed, Moto doesn't validate them:
   ```
   curl -X POST http://localhost:5000/ \
     -H "X-Amz-Target: secretsmanager.CreateSecret" \
     -H "Content-Type: application/x-amz-json-1.1" \
     -H "Authorization: AWS4-HMAC-SHA256 Credential=test/20260910/us-east-1/secretsmanager/aws4_request, SignedHeaders=host, Signature=dummy" \
     -d '{"Name":"demo-app-staging","SecretString":"{\"external.token\":\"moto-secret-token-123\",\"external.api-base\":\"https://mock.example.com\"}"}'
   ```

4. **Seed Moto (AWS Parameter Store)** — same Moto instance, different service (SSM). One call per parameter:
   ```
   curl -X POST http://localhost:5000/ \
     -H "X-Amz-Target: AmazonSSM.PutParameter" \
     -H "Content-Type: application/x-amz-json-1.1" \
     -H "Authorization: AWS4-HMAC-SHA256 Credential=test/20260910/us-east-1/ssm/aws4_request, SignedHeaders=host, Signature=dummy" \
     -d '{"Name":"/demo-app/staging/greeting","Value":"hello-from-parameter-store","Type":"String","Overwrite":true}'

   curl -X POST http://localhost:5000/ \
     -H "X-Amz-Target: AmazonSSM.PutParameter" \
     -H "Content-Type: application/x-amz-json-1.1" \
     -H "Authorization: AWS4-HMAC-SHA256 Credential=test/20260910/us-east-1/ssm/aws4_request, SignedHeaders=host, Signature=dummy" \
     -d '{"Name":"/demo-app/staging/db/password","Value":"ssm-secure-password","Type":"SecureString","Overwrite":true}'
   ```
   The second parameter becomes `db.password` in the merged response (nested path segments join with `.`).

5. **Git connector** — there's no bundled repo to point it at (see above), and `git` is one of the default active profiles, so `GIT_REPO_URI` is required (the app refuses to start without it, same as the other required variables — see [environment-variables.md](environment-variables.md)). Either point it at any git remote you control (`https://`, `ssh://`, or a local `file:` path to a repo you've initialized yourself) containing a `demo-app.yml` and/or `demo-app-staging.yml`, e.g. a quick scratch repo:
   ```
   mkdir -p /tmp/demo-git-repo && cd /tmp/demo-git-repo
   git init -b master
   echo 'demo: {source: git}' > demo-app.yml
   git add -A && git commit -m seed
   ```
   ...or skip it entirely by dropping `git` from `SPRING_PROFILES_ACTIVE` — then `GIT_REPO_URI` isn't required at all, and the merged response just has no Git-sourced property source.

6. **Run the server:**
   ```
   GIT_REPO_URI=file:///tmp/demo-git-repo \
   VAULT_HOST=127.0.0.1 VAULT_PORT=18200 VAULT_SCHEME=http \
   VAULT_TOKEN=root \
   SECURITY_PASSWORD=labpassword \
   AWS_SECRETSMANAGER_ENDPOINT=http://localhost:5000 \
   AWS_SECRETSMANAGER_REGION=us-east-1 \
   AWS_SECRETSMANAGER_CREDENTIALS_PROVIDER=static \
   AWS_SECRETSMANAGER_ACCESS_KEY=test AWS_SECRETSMANAGER_SECRET_KEY=test \
   AWS_PARAMETERSTORE_ENDPOINT=http://localhost:5000 \
   AWS_PARAMETERSTORE_REGION=us-east-1 \
   AWS_PARAMETERSTORE_CREDENTIALS_PROVIDER=static \
   AWS_PARAMETERSTORE_ACCESS_KEY=test AWS_PARAMETERSTORE_SECRET_KEY=test \
   ./gradlew bootRun
   ```
   Listens on `:8889`, Basic Auth `root` / `labpassword` (override the username via `SECURITY_USERNAME`). None of these has an insecure or demo-pointing default baked into `application.yml` on purpose — see [environment-variables.md](environment-variables.md) for why, and what a real deployment needs to set instead.
