package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.RedisConstant;
import com.sky.constant.WebSocketConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.mapper.UserMapper;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.OrderService;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.beans.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;  // 添加OrderDetailMapper注入
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Value("${sky.shop.address}")
    private String shopAddress;
    @Value("${sky.baidu.ak}")
    private String baiduAk;
    @Autowired
    private WebSocketServer webSocketServer;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 用户下单
     *
     * @param ordersSubmitDTO
     * @return
     */
    @Override
    @Transient
    public OrderSubmitVO submit(OrdersSubmitDTO ordersSubmitDTO) {
        //检查地址
        Long addressBookId = ordersSubmitDTO.getAddressBookId();
        AddressBook addressBook = addressBookMapper.getById(addressBookId);
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        // 配送距离校验
        // 拼接用户的完整收货地址 (省+市+区+详细地址)
        String userAddress = addressBook.getProvinceName() +
                addressBook.getCityName() +
                addressBook.getDistrictName() +
                addressBook.getDetail();

        //  插入自定义配送距离校验函数
        checkOutOfRange(userAddress);

        //获取并检查用户的购物车
        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);
        List<ShoppingCart> carts = shoppingCartMapper.list(shoppingCart);
        if (carts == null) {
            throw new AddressBookBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }


        //创建订单
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        orders.setUserId(userId);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setPayStatus(Orders.UN_PAID);
        orders.setOrderTime(LocalDateTime.now());
        // 修复：设置预计送达时间（默认1小时后）
        orders.setEstimatedDeliveryTime(LocalDateTime.now().plusHours(1));
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());
        // 修复：设置完整的地址信息
        orders.setAddress(addressBook.getProvinceName() + 
                         addressBook.getCityName() + 
                         addressBook.getDistrictName() + 
                         addressBook.getDetail());
        // 修复：设置用户名
        orders.setConsignee(addressBook.getConsignee());
        orders.setPhone(addressBook.getPhone());
        //生成订单号
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int random = ThreadLocalRandom.current().nextInt(1000, 10000); // 生成 1000-9999 之间的数
        String orderNumber = time + random;
        orders.setNumber(orderNumber);
        orderMapper.insert(orders);

        // 【新增逻辑】下单成功后，立刻将订单ID发到10秒延迟队列
        // 参数1: 正常交换机名称
        // 参数2: 10秒队列的路由键
        // 参数3: 要发送的消息内容（只发订单ID即可，越轻量越好）
        log.info("订单 {} 提交成功，准备发送10秒延迟检测消息", orders.getId());
        rabbitTemplate.convertAndSend("order.direct", "order.delay.10s", orders.getId());

        //创建详细订单信息列表
        List<OrderDetail> orderDetails = new ArrayList<>();
        for (ShoppingCart cart : carts) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(orders.getId());
            orderDetails.add(orderDetail);
        }
        orderMapper.insertOrderDetailBatch(orderDetails);

        //情空购物车
        shoppingCartMapper.deleteByUserId(userId);
        //返回结果
        OrderSubmitVO ordersSubmitVO = OrderSubmitVO.builder()
                .id(orders.getId())
                .orderNumber(orders.getNumber())
                .orderTime(orders.getOrderTime())
                .orderAmount(orders.getAmount())
                .build();
        log.info("用户下单成功：{}", ordersSubmitVO);
        return ordersSubmitVO;
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        // 调用微信支付接口的真实代码，这里由于缺少支付所需文件，采用模拟代码
//        //调用微信支付接口，生成预支付交易单
//        JSONObject jsonObject = weChatPayUtil.pay(
//                ordersPaymentDTO.getOrderNumber(), //商户订单号
//                new BigDecimal(0.01), //支付金额，单位 元
//                "苍穹外卖订单", //商品描述
//                user.getOpenid() //微信用户的openid
//        );
//
//        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
//            throw new OrderBusinessException("该订单已支付");
//        }
//
//        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
//        vo.setPackageStr(jsonObject.getString("package"));

        OrderPaymentVO vo = OrderPaymentVO.builder()
                .nonceStr("mock_nonceStr_" + System.currentTimeMillis())
                .paySign("mock_sign")
                .timeStamp(String.valueOf(System.currentTimeMillis() / 1000))
                .signType("RSA")
                .packageStr("prepay_id=mock_prepay_id")
                .build();

        // 【模拟核心步骤】直接强行调用“支付成功”的回调逻辑
        // 真实的场景下，这行代码是微信服务器通过 notifyUrl 异步调用的
        // 我们模拟的话，就在这里同步调用，瞬间把订单变成已支付！
        paySuccess(ordersPaymentDTO.getOrderNumber());
        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {

        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);

        //发送来单提醒
        //构造订单消息json字符串
        //构造消息map
        Map map = new HashMap();
        map.put("type", WebSocketConstant.NEW_ORDER );//订单类型，1-新订单
        map.put("orderId", ordersDB.getId());
        map.put("content", "订单号"+ordersDB.getNumber());

        //转换为json字符串
        String json = JSON.toJSONString(map);

        //向广播交换机发送消息
        log.info("【客户催单】将催单消息推送到 MQ 广播中心...");
        rabbitTemplate.convertAndSend("websocket.fanout", "", json);
    }

    /**
     * 分页搜索查询订单
     *
     * @param ordersPageQueryDTO
     * @return
     */
    public PageResult pageQuery(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);
        long total = page.getTotal();
        List<Orders> records = page.getResult();

        // 将Order和OrderDetail进行组装
        List<OrderVO> list = new ArrayList<>();
        for (Orders orders : records) {
            OrderVO orderVO = new OrderVO();
            BeanUtils.copyProperties(orders, orderVO);

            // 根据订单id查询订单详情
            List<OrderDetail> orderDetailList = orderDetailMapper.listByOrderId(orders.getId());
            orderVO.setOrderDetailList(orderDetailList);

            // 组装订单菜品信息字符串
            StringBuilder orderDishes = new StringBuilder();
            for (OrderDetail orderDetail : orderDetailList) {
                orderDishes.append(orderDetail.getName()).append("*").append(orderDetail.getNumber()).append("; ");
            }
            orderVO.setOrderDishes(orderDishes.toString());

            list.add(orderVO);
        }
        return new PageResult(total, list);
    }

    /**
     * 再来一单
     *
     * @param id 订单ID
     */
    @Override
    @Transactional
    public void repetition(Long id) {
        // 1. 获取当前登录用户ID
        Long userId = BaseContext.getCurrentId();
        ;

        // 2. 根据订单ID查询订单明细
        List<OrderDetail> orderDetailList = orderDetailMapper.listByOrderId(id);
        if (orderDetailList == null || orderDetailList.isEmpty()) {
            log.warn("订单ID {} 对应的订单明细为空", id);
            return;
        }

        // 3. 将订单明细对象转换为购物车对象
        List<ShoppingCart> shoppingCartList = orderDetailList.stream().map(orderDetail -> {
            ShoppingCart shoppingCart = new ShoppingCart();

            // 将原订单详情里的菜品/套餐信息复制到购物车对象中
            // 注意：忽略 id 属性，因为购物车表的主键需要自增
            BeanUtils.copyProperties(orderDetail, shoppingCart, "id");

            // 补充购物车所需的其他字段
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());

            return shoppingCart;
        }).collect(Collectors.toList());

        // 4. 将购物车对象批量插入到数据库
        shoppingCartMapper.insertBatch(shoppingCartList);

        log.info("再来一单成功，共添加 {} 个商品到购物车", shoppingCartList.size());
    }

    /**
     * 统计各个状态的订单数量
     *
     * @return 订单统计结果
     */
    @Override
    public OrderStatisticsVO getOrderStatistics() {
        OrderStatisticsVO statistics = orderMapper.getOrderStatistics();

        return statistics;
    }

    /**
     * 用户端订单分页查询
     *
     * @param pageNum
     * @param pageSize
     * @param status
     * @return
     */
    public PageResult pageQuery4User(int pageNum, int pageSize, Integer status) {
        // 设置分页
        PageHelper.startPage(pageNum, pageSize);

        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        // 分页条件查询
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        List<OrderVO> list = new ArrayList();

        // 查询出订单明细，并封装入OrderVO进行响应
        if (page != null && page.getTotal() > 0) {
            for (Orders orders : page) {
                Long orderId = orders.getId();// 订单id

                // 查询订单明细
                List<OrderDetail> orderDetails = orderDetailMapper.listByOrderId(orderId);

                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(orderDetails);

                list.add(orderVO);
            }
        }
        return new PageResult(page.getTotal(), list);
    }

    /**
     * 查询订单详情
     *
     * @param id
     * @return
     */
    public OrderVO details(Long id) {
        // 根据id查询订单
        Orders orders = orderMapper.getById(id);

        // 查询该订单对应的菜品/套餐明细
        List<OrderDetail> orderDetailList = orderDetailMapper.listByOrderId(orders.getId());

        // 将该订单及其详情封装到OrderVO并返回
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);

        return orderVO;
    }

    /**
     * 用户取消订单
     *
     * @param id
     */
    public void userCancelById(Long id) throws Exception {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 校验订单是否存在
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        //订单状态 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
        if (ordersDB.getStatus() > 2) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());

        // 订单处于待接单状态下取消，需要进行退款
        if (ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            log.info("模拟微信退款成功，订单号：{}", ordersDB.getNumber());
            //调用微信支付退款接口
//            weChatPayUtil.refund(
//                    ordersDB.getNumber(), //商户订单号
//                    ordersDB.getNumber(), //商户退款单号
//                    new BigDecimal(0.01),//退款金额，单位 元
//                    new BigDecimal(0.01));//原订单金额

            //支付状态修改为 退款
            orders.setPayStatus(Orders.REFUND);
        }

        // 更新订单状态、取消原因、取消时间
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 接单
     *
     * @param ordersConfirmDTO
     */
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders orders = Orders.builder()
                .id(ordersConfirmDTO.getId())
                .status(Orders.CONFIRMED)
                .build();

        orderMapper.update(orders);
    }

    /**
     * 拒单
     *
     * @param ordersRejectionDTO
     */
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(ordersRejectionDTO.getId());

        // 订单只有存在且状态为2（待接单）才可以拒单
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        //支付状态
        Integer payStatus = ordersDB.getPayStatus();
        if (payStatus == Orders.PAID) {
            //用户已支付，需要退款
            //模拟退款
            log.info("模拟退款成功");
            //实际退款操作
//            String refund = weChatPayUtil.refund(
//                    ordersDB.getNumber(),
//                    ordersDB.getNumber(),
//                    new BigDecimal(0.01),
//                    new BigDecimal(0.01));
//            log.info("申请退款：{}", refund);
            orders.setPayStatus(Orders.REFUND);
        }

        // 拒单需要退款，根据订单id更新订单状态、拒单原因、取消时间
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    /**
     * 取消订单
     *
     * @param ordersCancelDTO
     */
    public void cancel(OrdersCancelDTO ordersCancelDTO) throws Exception {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(ordersCancelDTO.getId());

        Orders orders = new Orders();
        //支付状态
        Integer payStatus = ordersDB.getPayStatus();
        if (payStatus == 1) {
            log.info("模拟微信退款成功，订单号：{}", ordersDB.getNumber());
            //用户已支付，需要退款
//            String refund = weChatPayUtil.refund(
//                    ordersDB.getNumber(),
//                    ordersDB.getNumber(),
//                    new BigDecimal(0.01),
//                    new BigDecimal(0.01));
//            log.info("申请退款：{}", refund);
            orders.setPayStatus(Orders.REFUND);
        }

        // 管理端取消订单需要退款，根据订单id更新订单状态、取消原因、取消时间
        orders.setId(ordersCancelDTO.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersCancelDTO.getCancelReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 派送订单
     *
     * @param id
     */
    public void delivery(Long id) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 校验订单是否存在，并且状态为3
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        // 更新订单状态,状态转为派送中
        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);

        orderMapper.update(orders);

        // 【新增逻辑】下单成功后，立刻将订单ID发到60分钟延迟队列
        log.info("订单 {} 提交成功，准备发送60分钟延迟检测消息", orders.getId());
        rabbitTemplate.convertAndSend("order.direct", "order.delay.60m", orders.getId());

    }

    /**
     * 完成订单
     *
     * @param id
     */
    public void complete(Long id) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 校验订单是否存在，并且状态为4
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        // 更新订单状态,状态转为完成
        orders.setStatus(Orders.COMPLETED);
        orders.setDeliveryTime(LocalDateTime.now());

        orderMapper.update(orders);

        List<OrderDetail> orderDetails = orderDetailMapper.listByOrderId(id);
        LocalDate orderDate = ordersDB.getOrderTime().toLocalDate();
        String zsetKey = RedisConstant.SALES_KEY + orderDate;

        for (OrderDetail detail : orderDetails) {
            redisTemplate.opsForZSet().incrementScore(
                zsetKey,
                detail.getName(),
                detail.getNumber()
            );
        }

        redisTemplate.expire(zsetKey, RedisConstant.SALES_EXPIRE_DAYS, TimeUnit.DAYS);
        log.info("订单完成，已更新商品销量 ZSET: {}", zsetKey);
    }

    /**
     * 校验用户的收货地址是否超出5公里配送范围
     *
     * @param userAddress 用户的详细收货地址
     */
    private void checkOutOfRange(String userAddress) {
        // 1. 调用百度地图地理编码接口，获取商家和用户的经纬度
        String shopCoordinate = getCoordinate(shopAddress);
        String userCoordinate = getCoordinate(userAddress);

        // 2. 调用百度地图轻量级路线规划接口（骑行），计算距离
        // 接口要求的参数格式为：纬度,经度 (lat,lng)
        Map<String, String> map = new HashMap<>();
        map.put("origin", shopCoordinate);
        map.put("destination", userCoordinate);
        map.put("ak", baiduAk);

        // 骑行路线规划接口
        String directionUrl = "https://api.map.baidu.com/directionlite/v1/riding";
        String responseString = HttpClientUtil.doGet(directionUrl, map);

        JSONObject jsonObject = JSON.parseObject(responseString);
        if (!jsonObject.getString("status").equals("0")) {
            throw new OrderBusinessException("配送路线规划失败");
        }

        // 解析出距离（单位：米）
        JSONObject result = jsonObject.getJSONObject("result");
        JSONArray routes = result.getJSONArray("routes");
        Integer distance = ((JSONObject) routes.get(0)).getInteger("distance");

        log.info("商家到用户的骑行距离为：{}米", distance);

        // 3. 校验距离是否大于5000米 (5公里)
        if (distance > 5000) {
            throw new OrderBusinessException("超出配送范围，下单失败");
        }
    }

    /**
     * 根据地址获取经纬度坐标 (lat,lng)
     */
    private String getCoordinate(String address) {
        Map<String, String> map = new HashMap<>();
        map.put("address", address);
        map.put("output", "json");
        map.put("ak", baiduAk);

        String geocodingUrl = "https://api.map.baidu.com/geocoding/v3";
        String responseString = HttpClientUtil.doGet(geocodingUrl, map);

        JSONObject jsonObject = JSON.parseObject(responseString);
        if (!jsonObject.getString("status").equals("0")) {
            throw new OrderBusinessException("地址解析失败");
        }

        JSONObject location = jsonObject.getJSONObject("result").getJSONObject("location");
        String lat = location.getString("lat"); // 纬度
        String lng = location.getString("lng"); // 经度

        // 百度路线规划接口要求格式：lat,lng
        return lat + "," + lng;
    }

    /**
     * 用户催单
     * @param id
     */
     public void reminder(Long id) {
         // 根据id查询订单
         Orders ordersDB = orderMapper.getById(id);

         // 校验订单是否存在，并且状态为3
         if (ordersDB == null || !ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
             throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
         }

         //构建消息
         Map map = new HashMap();
         map.put("type", WebSocketConstant.ORDER_REMINDER);
         map.put("orderId", id);
         map.put("content", "订单号：" + ordersDB.getNumber());

         //转换为json字符串
         String json = JSON.toJSONString(map);

         //原代码
         //webSocketServer.sendToAllClient(json);
         //直接发送难以面对多服务端架构


         //【新架构代码】推送到 MQ 广播中心
         log.info("【来单提醒】将新订单消息推送到 MQ 广播中心...");
         rabbitTemplate.convertAndSend("websocket.fanout", "", json);
     }
}
