/* 官方 integrated-example 本机学习适配：保持表单 POST 下单接口。 */
'use strict';

document.getElementById('orderForm').addEventListener('submit', async event => {
    event.preventDefault();
    const section = document.getElementById('orderResultSection');
    const button = document.getElementById('startButton');
    const fields = Object.fromEntries(new FormData(event.currentTarget));
    section.replaceChildren();
    button.disabled = true;

    async function queryState(phase) {
        const storage = await demo.request('/storage/', {commodityCode: fields.commodityCode});
        demo.log(section, phase + '商品库存：' + storage.data);
        const account = await demo.request('/account/', {userId: fields.userId});
        demo.log(section, phase + '账户余额：' + account.data);
    }

    try {
        await queryState('下单前');
        try {
            const result = await demo.request('/order/create', fields, 'POST');
            demo.log(section, result.message || '下单请求已完成');
        } catch (error) {
            demo.log(section, '下单失败：' + error.message);
        }
        await queryState('下单后');
    } catch (error) {
        demo.log(section, error.message);
    } finally {
        button.disabled = false;
    }
});

