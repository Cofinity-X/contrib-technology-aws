/*
 *  Copyright (c) 2020 - 2023 Microsoft Corporation
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Microsoft Corporation - initial API and implementation
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG)
 *
 */

package org.eclipse.edc.aws.s3.spi;

public interface S3BucketSchema {
    String TYPE = "AmazonS3";
    String REGION = "region";
    String BUCKET_NAME = "bucketName";
    String FOLDER_NAME = "folderName";
    String ACCESS_KEY_ID = "accessKeyId";
    String SECRET_ACCESS_KEY = "secretAccessKey";
    String ENDPOINT_OVERRIDE = "endpointOverride";
    String OBJECT_PREFIX = "objectPrefix";
    String OBJECT_NAME = "objectName";
    String SECRET_ACCESS_ALIAS_PREFIX = "secretAccessAlias-";
}
