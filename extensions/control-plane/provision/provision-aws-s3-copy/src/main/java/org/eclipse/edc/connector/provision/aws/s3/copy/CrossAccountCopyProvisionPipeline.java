package org.eclipse.edc.connector.provision.aws.s3.copy;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import jakarta.json.Json;
import org.eclipse.edc.aws.s3.AwsClientProvider;
import org.eclipse.edc.aws.s3.AwsTemporarySecretToken;
import org.eclipse.edc.aws.s3.S3ClientRequest;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.types.TypeManager;
import org.eclipse.edc.util.string.StringUtils;
import software.amazon.awssdk.services.iam.IamAsyncClient;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.CreateRoleResponse;
import software.amazon.awssdk.services.iam.model.GetUserResponse;
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.sts.StsAsyncClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static org.eclipse.edc.aws.s3.spi.S3BucketSchema.BUCKET_NAME;
import static org.eclipse.edc.connector.provision.aws.s3.copy.util.S3CopyTemplates.BUCKET_POLICY_STATEMENT_TEMPLATE;
import static org.eclipse.edc.connector.provision.aws.s3.copy.util.S3CopyTemplates.CROSS_ACCOUNT_ROLE_POLICY_TEMPLATE;
import static org.eclipse.edc.connector.provision.aws.s3.copy.util.S3CopyTemplates.CROSS_ACCOUNT_ROLE_TRUST_POLICY_TEMPLATE;
import static org.eclipse.edc.connector.provision.aws.s3.copy.util.S3CopyTemplates.EMPTY_BUCKET_POLICY;

public class CrossAccountCopyProvisionPipeline {
    
    private final AwsClientProvider clientProvider;
    private final Vault vault;
    private final RetryPolicy<Object> retryPolicy;
    private final TypeManager typeManager;
    private final Monitor monitor;
    
    private CrossAccountCopyProvisionPipeline(AwsClientProvider clientProvider, Vault vault, RetryPolicy<Object> retryPolicy, TypeManager typeManager, Monitor monitor) {
        this.clientProvider = clientProvider;
        this.vault = vault;
        this.retryPolicy = retryPolicy;
        this.typeManager = typeManager;
        this.monitor = monitor;
    }
    
    public CompletableFuture<S3ProvisionResponse> provision(CrossAccountCopyResourceDefinition resourceDefinition) {
        var iamClient = clientProvider.iamAsyncClient();
        //TODO region for sts should be configurable -> choose region closest to where EDC deployed
        var stsClient = clientProvider.stsAsyncClient("eu-central-1");
        
        var secretToken = getTemporarySecretToken(resourceDefinition.getDestinationKeyName());
        var s3ClientRequest = S3ClientRequest.from(resourceDefinition.getDestinationRegion(), null, secretToken);
        var s3Client = clientProvider.s3AsyncClient(s3ClientRequest);
        
        monitor.debug("S3 CrossAccountCopyProvisioner: getting IAM user");
        return iamClient.getUser()
                .thenCompose(response -> createRole(iamClient, resourceDefinition, response))
                .thenCompose(response -> putRolePolicy(iamClient, resourceDefinition, response))
                .thenCompose(provisionDetails -> getDestinationBucketPolicy(s3Client, resourceDefinition, provisionDetails))
                .thenCompose(provisionDetails -> updateDestinationBucketPolicy(s3Client, resourceDefinition, provisionDetails))
                .thenCompose(role -> assumeRole(stsClient, resourceDefinition, role));
    }
    
    private CompletableFuture<CreateRoleResponse> createRole(IamAsyncClient iamClient,
                                                             CrossAccountCopyResourceDefinition resourceDefinition,
                                                             GetUserResponse getUserResponse) {
        var roleName = roleIdentifier(resourceDefinition);
        var trustPolicy = CROSS_ACCOUNT_ROLE_TRUST_POLICY_TEMPLATE
                .replace("{{user-arn}}", getUserResponse.user().arn());
        
        return Failsafe.with(retryPolicy).getStageAsync(() -> {
            var createRoleRequest = CreateRoleRequest.builder()
                    .roleName(roleName)
                    .description(format("Role for EDC transfer: %s", roleName))
                    .assumeRolePolicyDocument(trustPolicy)
                    .build();
            
            monitor.debug(format("S3 CrossAccountCopyProvisioner: creating IAM role '%s'", roleName));
            return iamClient.createRole(createRoleRequest);
        });
    }
    
    private CompletableFuture<CrossAccountCopyProvisionSteps> putRolePolicy(IamAsyncClient iamClient,
                                                                            CrossAccountCopyResourceDefinition resourceDefinition,
                                                                            CreateRoleResponse createRoleResponse) {
        var rolePolicy = CROSS_ACCOUNT_ROLE_POLICY_TEMPLATE
                .replace("{{source-bucket}}", resourceDefinition.getSourceDataAddress().getStringProperty(BUCKET_NAME))
                .replace("{{destination-bucket}}", resourceDefinition.getDestinationBucketName());
        
        return Failsafe.with(retryPolicy).getStageAsync(() -> {
            Role role = createRoleResponse.role();
            var putRolePolicyRequest = PutRolePolicyRequest.builder()
                    .roleName(createRoleResponse.role().roleName())
                    .policyName(roleIdentifier(resourceDefinition))
                    .policyDocument(rolePolicy)
                    .build();
            
            monitor.debug("S3 CrossAccountCopyProvisioner: putting IAM role policy");
            return iamClient.putRolePolicy(putRolePolicyRequest)
                    .thenApply(policyResponse -> new CrossAccountCopyProvisionSteps(role));
        });
    }
    
    private CompletableFuture<CrossAccountCopyProvisionSteps> getDestinationBucketPolicy(S3AsyncClient s3Client,
                                                                                         CrossAccountCopyResourceDefinition resourceDefinition,
                                                                                         CrossAccountCopyProvisionSteps provisionDetails) {
        var getBucketPolicyRequest = GetBucketPolicyRequest.builder()
                .bucket(resourceDefinition.getDestinationBucketName())
                .build();
        
        return Failsafe.with(retryPolicy).getStageAsync(() -> {
            monitor.debug("S3 CrossAccountCopyProvisioner: getting destination bucket policy");
            return s3Client.getBucketPolicy(getBucketPolicyRequest)
                    .handle((result, ex) -> {
                        if (ex == null) {
                            provisionDetails.setBucketPolicy(result.policy());
                            return provisionDetails;
                        } else {
                            provisionDetails.setBucketPolicy(EMPTY_BUCKET_POLICY);
                            return provisionDetails;
                        }
                    });
        });
    }
    
    private CompletableFuture<CrossAccountCopyProvisionSteps> updateDestinationBucketPolicy(S3AsyncClient s3Client,
                                                                                            CrossAccountCopyResourceDefinition resourceDefinition,
                                                                                            CrossAccountCopyProvisionSteps provisionDetails) {
        var bucketPolicyStatement = BUCKET_POLICY_STATEMENT_TEMPLATE
                .replace("{{sid}}", resourceDefinition.getBucketPolicyStatementSid())
                .replace("{{source-account-role-arn}}", provisionDetails.getRole().arn())
                .replace("{{sink-bucket-name}}", resourceDefinition.getDestinationBucketName());
        
        var typeReference = new TypeReference<HashMap<String,Object>>() {};
        var statementJson = Json.createObjectBuilder(typeManager.readValue(bucketPolicyStatement, typeReference)).build();
        var policyJson = Json.createObjectBuilder(typeManager.readValue(provisionDetails.getBucketPolicy(), typeReference)).build();
        
        var statements = Json.createArrayBuilder(policyJson.getJsonArray("Statement"))
                .add(statementJson)
                .build();
        var updatedBucketPolicy = Json.createObjectBuilder(policyJson)
                .add("Statement", statements)
                .build().toString();
        
        return Failsafe.with(retryPolicy).getStageAsync(() -> {
            monitor.debug("S3 CrossAccountCopyProvisioner: updating destination bucket policy");
            var putBucketPolicyRequest = PutBucketPolicyRequest.builder()
                    .bucket(resourceDefinition.getDestinationBucketName())
                    .policy(updatedBucketPolicy)
                    .build();
            return s3Client.putBucketPolicy(putBucketPolicyRequest)
                    .thenApply(response -> provisionDetails);
        });
    }
    
    private CompletableFuture<S3ProvisionResponse> assumeRole(StsAsyncClient stsClient,
                                                              CrossAccountCopyResourceDefinition resourceDefinition,
                                                              CrossAccountCopyProvisionSteps provisionDetails) {
        return Failsafe.with(retryPolicy).getStageAsync(() -> {
            var role = provisionDetails.getRole();
            var assumeRoleRequest = AssumeRoleRequest.builder()
                    .roleArn(role.arn())
                    .roleSessionName(roleIdentifier(resourceDefinition))
                    .build();
            
            monitor.debug(format("S3 CrossAccountCopyProvisioner: assuming role '%s'", role.arn()));
            return stsClient.assumeRole(assumeRoleRequest)
                    .thenApply(response -> new S3ProvisionResponse(role, response.credentials()));
        });
    }
    
    private String roleIdentifier(CrossAccountCopyResourceDefinition resourceDefinition) {
        return format("edc-transfer-role_%s", resourceDefinition.getTransferProcessId());
    }
    
    private AwsTemporarySecretToken getTemporarySecretToken(String secretKeyName) {
        return ofNullable(secretKeyName)
                .filter(keyName -> !StringUtils.isNullOrBlank(keyName))
                .map(vault::resolveSecret)
                .filter(secret -> !StringUtils.isNullOrBlank(secret))
                .map(secret -> typeManager.readValue(secret, AwsTemporarySecretToken.class))
                .orElse(null);
    }
    
    static class Builder {
        private AwsClientProvider clientProvider;
        private Vault vault;
        private RetryPolicy<Object> retryPolicy;
        private TypeManager typeManager;
        private Monitor monitor;
        
        private Builder() {}
        
        public static Builder newInstance() {
            return new Builder();
        }
        
        public Builder clientProvider(AwsClientProvider clientProvider) {
            this.clientProvider = clientProvider;
            return this;
        }
    
        public Builder vault(Vault vault) {
            this.vault = vault;
            return this;
        }
    
        public Builder retryPolicy(RetryPolicy<Object> retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }
    
        public Builder typeManager(TypeManager typeManager) {
            this.typeManager = typeManager;
            return this;
        }
    
        public Builder monitor(Monitor monitor) {
            this.monitor = monitor;
            return this;
        }
        
        public CrossAccountCopyProvisionPipeline build() {
            Objects.requireNonNull(clientProvider);
            Objects.requireNonNull(vault);
            Objects.requireNonNull(retryPolicy);
            Objects.requireNonNull(typeManager);
            Objects.requireNonNull(monitor);
            return new CrossAccountCopyProvisionPipeline(clientProvider, vault, retryPolicy, typeManager, monitor);
        }
    }
}
