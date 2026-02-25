.PHONY: build test clean docker-build docker-push deploy

IMAGE ?= quay.io/kevindubois/kubernetes-agent:latest

CONTEXT ?= $(shell kubectl config current-context)

build:
	mvn clean package

test:
	mvn test

clean:
	mvn clean

docker-build:
	docker build -t $(IMAGE) -f src/main/docker/Dockerfile.jvm .

docker-push:
	docker push $(IMAGE)

deploy:
	kubectl --context $(CONTEXT) apply -k deployment/

undeploy:
	kubectl --context $(CONTEXT) delete -k deployment/

logs:
	kubectl --context $(CONTEXT) logs -f deployment/kubernetes-agent -n argo-rollouts

health:
	kubectl port-forward -n argo-rollouts svc/kubernetes-agent 8080:8080 &
	sleep 2
	curl http://localhost:8080/a2a/health
	pkill -f "port-forward.*kubernetes-agent"

kind-load:
	kind load docker-image $(IMAGE) --name rollouts-plugin-metric-ai-test-e2e

.PHONY: redeploy
redeploy: docker-build kind-load deploy
	kubectl --context $(CONTEXT) rollout restart deployment/kubernetes-agent -n argo-rollouts
