/* 官方 integrated-example 本机学习适配：统一请求和中文错误展示。 */
'use strict';

window.demo = (() => {
    const gateway = (window.demoGatewayBaseUrl || 'http://127.0.0.1:18085').replace(/\/+$/, '');

    async function request(path, parameters, method = 'GET') {
        const data = new URLSearchParams(parameters);
        const options = {method};
        let url = gateway + path;
        if (method === 'GET') {
            url += '?' + data.toString();
        } else {
            options.body = data;
        }
        let response;
        try {
            response = await fetch(url, options);
        } catch (error) {
            throw new Error('网络请求失败，请确认网关 ' + gateway + ' 及对应服务已启动');
        }
        const text = await response.text();
        let result;
        try {
            result = text ? JSON.parse(text) : null;
        } catch (error) {
            throw new Error('HTTP ' + response.status + '：服务返回了无法解析的响应');
        }
        const message = result && (result.message || result.detail || result.error);
        if (!response.ok) {
            throw new Error('HTTP ' + response.status + '：' + (message || '请求失败，请检查对应服务'));
        }
        if (result && typeof result.code === 'number' && result.code !== 2001 && result.code !== 200) {
            throw new Error(message || '业务执行失败');
        }
        return result;
    }

    function log(section, message) {
        const paragraph = document.createElement('p');
        paragraph.textContent = '[' + new Date().toLocaleTimeString() + '] ' + message;
        section.appendChild(paragraph);
        // 长时间轮询时保留有限记录，避免演示页持续占用内存。
        while (section.childElementCount > 100) {
            section.firstElementChild.remove();
        }
    }

    function itemId() {
        const value = Number(document.getElementById('itemId').value);
        if (!Number.isSafeInteger(value) || value < 1 || value > 2147483647) {
            throw new Error('商品 ID 必须为正整数且不能超过 2147483647');
        }
        return value;
    }

    async function praise(path, id) {
        const sent = await request(path, {itemId: id});
        if (sent !== true) {
            throw new Error('消息发送失败，请检查 RocketMQ 和点赞服务日志');
        }
    }

    return {request, log, itemId, praise};
})();

