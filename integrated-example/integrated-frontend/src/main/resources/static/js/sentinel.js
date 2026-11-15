/* 官方 integrated-example 本机学习适配：展示成功、限流和其它错误。 */
'use strict';

document.getElementById('startButton').addEventListener('click', async event => {
    const section = document.getElementById('sentinelResultSection');
    const button = event.currentTarget;
    section.replaceChildren();
    button.disabled = true;
    try {
        const id = demo.itemId();
        demo.log(section, '点赞前的点赞数：' + await demo.request('/praise/query', {itemId: id}));
        await Promise.all(Array.from({length: 10}, async (_, index) => {
            try {
                await demo.praise('/praise/sentinel', id);
                demo.log(section, '第 ' + (index + 1) + ' 次请求：消息发送成功');
            } catch (error) {
                demo.log(section, '第 ' + (index + 1) + ' 次请求：' + error.message);
            }
        }));
        await new Promise(resolve => setTimeout(resolve, 1000));
        demo.log(section, '当前点赞数（异步消费可能尚未完成）：' + await demo.request('/praise/query', {itemId: id}));
    } catch (error) {
        demo.log(section, error.message);
    } finally {
        button.disabled = false;
    }
});

