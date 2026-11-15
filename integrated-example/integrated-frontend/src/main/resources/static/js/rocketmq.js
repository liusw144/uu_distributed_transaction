/* 官方 integrated-example 本机学习适配：仅点击后请求，轮询和错误有界。 */
'use strict';

document.getElementById('startButton').addEventListener('click', async event => {
    const section = document.getElementById('rocketmqResultSection');
    const button = event.currentTarget;
    section.replaceChildren();
    button.disabled = true;
    let timer;
    let polling = false;
    let pollCount = 0;
    let successful = 0;
    let failed = 0;
    try {
        const id = demo.itemId();
        const before = await demo.request('/praise/query', {itemId: id});
        demo.log(section, '点赞前的点赞数：' + before);

        async function queryProgress() {
            if (polling) {
                return;
            }
            polling = true;
            pollCount++;
            try {
                const count = await demo.request('/praise/query', {itemId: id});
                demo.log(section, '当前点赞数：' + count + '；消息已发送：' + successful + '，发送失败：' + failed);
            } catch (error) {
                demo.log(section, '查询失败：' + error.message);
                clearInterval(timer);
            } finally {
                polling = false;
            }
            if (pollCount >= 40) {
                clearInterval(timer);
            }
        }

        timer = setInterval(queryProgress, 500);
        await Promise.all(Array.from({length: 1000}, async () => {
            try {
                await demo.praise('/praise/rocketmq', id);
                successful++;
            } catch (error) {
                failed++;
                if (failed <= 5) {
                    demo.log(section, error.message);
                }
            }
        }));
        demo.log(section, '提交完成：成功 ' + successful + '，失败 ' + failed + '。消费结果可能延迟更新。');
        await new Promise(resolve => setTimeout(resolve, 3000));
        await queryProgress();
    } catch (error) {
        demo.log(section, error.message);
    } finally {
        clearInterval(timer);
        button.disabled = false;
    }
});

