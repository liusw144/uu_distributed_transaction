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
 * 统一业务结果编码。 本地学习修改：保持上游 2001/2003 及可读写 API，HTTP 状态与业务编码分别表达。
 *
 * @author TrevorLink
 */
public enum ResultEnum implements IResult {

    /**
     * 接口业务执行成功。
     */
    SUCCESS(2001, "接口调用成功"),

    /**
     * 接口业务执行失败。
     */
    COMMON_FAILED(2003, "接口调用失败");

    private Integer code;

    private String message;

    ResultEnum(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    /**
     * 设置业务结果编码，保留上游 API。
     *
     * @param code 业务结果编码
     */
    public void setCode(Integer code) {
        this.code = code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    /**
     * 设置业务结果说明，保留上游 API。
     *
     * @param message 业务结果说明
     */
    public void setMessage(String message) {
        this.message = message;
    }
}
