/*
 *  Copyright (c) 2025 Cofinity-X
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Cofinity-X - initial API and implementation
 *
 */

package org.eclipse.edc.connector.provision.aws.s3.copy;

import org.eclipse.edc.aws.s3.spi.S3BucketSchema;
import org.eclipse.edc.connector.controlplane.transfer.spi.provision.ProviderResourceDefinitionGenerator;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.ResourceDefinition;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcess;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.jetbrains.annotations.Nullable;

import static java.lang.String.format;
import static java.util.UUID.randomUUID;
import static org.eclipse.edc.aws.s3.spi.S3BucketSchema.BUCKET_NAME;
import static org.eclipse.edc.aws.s3.spi.S3BucketSchema.ENDPOINT_OVERRIDE;
import static org.eclipse.edc.aws.s3.spi.S3BucketSchema.FOLDER_NAME;
import static org.eclipse.edc.aws.s3.spi.S3BucketSchema.OBJECT_NAME;
import static org.eclipse.edc.aws.s3.spi.S3BucketSchema.REGION;
import static org.eclipse.edc.connector.provision.aws.s3.copy.util.S3CopyProvisionUtils.resourceIdentifier;

/**
 * Generates information for provisioning AWS resources for a cross-account copy of S3 objects.
 */
public class S3CopyResourceDefinitionGenerator implements ProviderResourceDefinitionGenerator {
    
    @Override
    public @Nullable ResourceDefinition generate(TransferProcess transferProcess, DataAddress assetAddress, Policy policy) {
        var bucketPolicyStatementSid = resourceIdentifier(transferProcess.getId());
        
        var destination = transferProcess.getDataDestination();

        var destinationKey = destination.getStringProperty(OBJECT_NAME) != null ?
                destination.getStringProperty(OBJECT_NAME) : transferProcess.getContentDataAddress().getStringProperty(OBJECT_NAME);
        var destinationFileName = getDestinationFileName(destinationKey, destination.getStringProperty(FOLDER_NAME));
        
        return S3CopyResourceDefinition.Builder.newInstance()
                .id(randomUUID().toString())
                .endpointOverride(destination.getStringProperty(ENDPOINT_OVERRIDE))
                .destinationRegion(destination.getStringProperty(REGION))
                .destinationBucketName(destination.getStringProperty(BUCKET_NAME))
                .destinationObjectName(destinationFileName)
                .destinationKeyName(destination.getKeyName())
                .bucketPolicyStatementSid(bucketPolicyStatementSid)
                .sourceDataAddress(transferProcess.getContentDataAddress())
                .build();
    }
    
    @Override
    public boolean canGenerate(TransferProcess transferProcess, DataAddress assetAddress, Policy policy) {
        if (transferProcess.getDataDestination() == null) {
            return false;
        }
        
        var sourceType = transferProcess.getContentDataAddress().getType();
        var sinkType = transferProcess.getDestinationType();
        var sourceEndpointOverride = transferProcess.getContentDataAddress().getStringProperty(ENDPOINT_OVERRIDE);
        var destinationEndpointOverride = transferProcess.getDataDestination().getStringProperty(ENDPOINT_OVERRIDE);
        
        // only applicable for S3-to-S3 transfer
        var isSameType = S3BucketSchema.TYPE.equals(sourceType) && S3BucketSchema.TYPE.equals(sinkType);
        
        // if endpointOverride set, it needs to be the same for both source & destination
        var hasSameEndpointOverride = sameEndpointOverride(sourceEndpointOverride, destinationEndpointOverride);
        
        return isSameType && hasSameEndpointOverride;
    }
    
    private String getDestinationFileName(String key, String folder) {
        if (folder == null) {
            return key;
        }
        
        return folder.endsWith("/") ? folder + key : format("%s/%s", folder, key);
    }
    
    private boolean sameEndpointOverride(String source, String destination) {
        if (source == null && destination == null) {
            return true;
        } else if (source == null || destination == null) {
            return false;
        } else {
            return source.equals(destination);
        }
    }
}
