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
 * 统一接口响应。 本地学习修改：仅维护原有结果 API 和说明，不引入 Web、数据库或 Seata 依赖。
 *
 * @param <T> 响应数据类型
 * @author TrevorLink
 */
public class Result<T> {

    private Integer code;

    private String message;

    private T data;

    /**
     * 创建成功响应。
     *
     * @param data 响应数据
     * @param <T>  响应数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultEnum.SUCCESS.getCode(), ResultEnum.SUCCESS.getMessage(), data);
    }

    /**
     * 创建自定义提示的成功响应。
     *
     * @param message 响应说明
     * @param data    响应数据
     * @param <T>     响应数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ResultEnum.SUCCESS.getCode(), message, data);
    }

    /**
     * 创建默认失败响应。
     *
     * @return 失败响应
     */
    public static Result<?> failed() {
        return new Result<>(ResultEnum.COMMON_FAILED.getCode(), ResultEnum.COMMON_FAILED.getMessage(), null);
    }

    /**
     * 创建带业务说明的失败响应。
     *
     * @param message 失败原因
     * @return 失败响应
     */
    public static Result<?> failed(String message) {
        return new Result<>(ResultEnum.COMMON_FAILED.getCode(), message, null);
    }

    /**
     * 按结果约定创建失败响应。
     *
     * @param errorResult 错误编码及说明
     * @return 失败响应
     */
    public static Result<?> failed(IResult errorResult) {
        return new Result<>(errorResult.getCode(), errorResult.getMessage(), null);
    }

    /**
     * 创建空响应，供 JSON 反序列化使用。
     */
    public Result() {
    }

    /**
     * 创建完整响应。
     *
     * @param code    业务结果编码
     * @param message 响应说明
     * @param data    响应数据
     */
    public Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 获取业务结果编码。
     *
     * @return 业务结果编码
     */
    public Integer getCode() {
        return code;
    }

    /**
     * 设置业务结果编码。
     *
     * @param code 业务结果编码
     */
    public void setCode(Integer code) {
        this.code = code;
    }

    /**
     * 获取响应说明。
     *
     * @return 响应说明
     */
    public String getMessage() {
        return message;
    }

    /**
     * 设置响应说明。
     *
     * @param message 响应说明
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * 获取响应数据。
     *
     * @return 响应数据
     */
    public T getData() {
        return data;
    }

    /**
     * 设置响应数据。
     *
     * @param data 响应数据
     */
    public void setData(T data) {
        this.data = data;
    }

    /**
     * 按指定编码创建响应。
     *
     * @param code    业务结果编码
     * @param message 响应说明
     * @param data    响应数据
     * @param <T>     响应数据类型
     * @return 完整响应
     */
    public static <T> Result<T> instance(Integer code, String message, T data) {
        return new Result<>(code, message, data);
    }
}
