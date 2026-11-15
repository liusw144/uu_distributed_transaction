/*
 * Copyright 2013-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.integration.common;

/**
 * 可向调用方说明的业务异常。 本地学习修改：保留原有构造方式并支持异常原因，使远程调用失败触发事务回滚且保留排查线索。
 *
 * @author TrevorLink
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 创建业务异常。
     *
     * @param message 业务错误说明
     */
    public BusinessException(String message) {
        super(message);
    }

    /**
     * 创建带原始原因的业务异常。
     *
     * @param message 业务错误说明
     * @param cause   原始异常
     */
    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
