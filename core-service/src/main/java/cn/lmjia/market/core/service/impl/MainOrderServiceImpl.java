package cn.lmjia.market.core.service.impl;

import cn.lmjia.market.core.entity.Customer;
import cn.lmjia.market.core.entity.Login;
import cn.lmjia.market.core.entity.MainGood;
import cn.lmjia.market.core.entity.MainGood_;
import cn.lmjia.market.core.entity.MainOrder;
import cn.lmjia.market.core.entity.MainOrder_;
import cn.lmjia.market.core.entity.MainProduct;
import cn.lmjia.market.core.entity.support.OrderStatus;
import cn.lmjia.market.core.event.MainOrderDeliveredEvent;
import cn.lmjia.market.core.event.MainOrderFinishEvent;
import cn.lmjia.market.core.exception.UnnecessaryShipException;
import cn.lmjia.market.core.jpa.JpaFunctionUtils;
import cn.lmjia.market.core.repository.MainOrderRepository;
import cn.lmjia.market.core.service.CustomerService;
import cn.lmjia.market.core.service.LoginService;
import cn.lmjia.market.core.service.MainOrderService;
import me.jiangcai.jpa.entity.support.Address;
import me.jiangcai.logistics.LogisticsService;
import me.jiangcai.logistics.LogisticsSupplier;
import me.jiangcai.logistics.StockService;
import me.jiangcai.logistics.Thing;
import me.jiangcai.logistics.entity.Depot;
import me.jiangcai.logistics.entity.Product;
import me.jiangcai.logistics.entity.StockShiftUnit;
import me.jiangcai.logistics.entity.UsageStock_;
import me.jiangcai.logistics.entity.support.ProductStatus;
import me.jiangcai.logistics.entity.support.ShiftStatus;
import me.jiangcai.logistics.event.InstallationEvent;
import me.jiangcai.logistics.event.ShiftEvent;
import me.jiangcai.logistics.exception.StockOverrideException;
import me.jiangcai.logistics.haier.HaierSupplier;
import me.jiangcai.logistics.option.LogisticsOptions;
import me.jiangcai.logistics.repository.DepotRepository;
import me.jiangcai.wx.model.Gender;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.NumberUtils;
import org.springframework.util.StringUtils;

import javax.persistence.EntityManager;
import javax.persistence.EntityNotFoundException;
import javax.persistence.NoResultException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.CriteriaUpdate;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author CJ
 */
@Service
public class MainOrderServiceImpl implements MainOrderService {

    private static final Log log = LogFactory.getLog(MainOrderServiceImpl.class);

    @Autowired
    private CustomerService customerService;
    @Autowired
    private LoginService loginService;
    @Autowired
    private MainOrderRepository mainOrderRepository;
    @SuppressWarnings("SpringJavaAutowiringInspection")
    @Autowired
    private EntityManager entityManager;
    /**
     * 保存每日序列号的
     */
    private Map<LocalDate, AtomicInteger> dailySerials = Collections.synchronizedMap(new HashMap<>());
    @Autowired
    private StockService stockService;
    @Autowired
    private HaierSupplier haierSupplier;
    @Autowired
    private LogisticsService logisticsService;
    @Autowired
    private DepotRepository depotRepository;
    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;
    @Autowired
    private ApplicationContext applicationContext;

    @Override
    public MainOrder newOrder(Login who, Login recommendBy, String name, String mobile, int age, Gender gender
            , Address installAddress, Map<MainGood, Integer> amounts, String mortgageIdentifier) {
        // 客户处理
        Customer customer = customerService.getNoNullCustomer(name, mobile, loginService.lowestAgentLevel(getEnjoyability(who))
                , recommendBy);

        customer.setInstallAddress(installAddress);
        customer.setGender(gender);
        final LocalDate now = LocalDate.now();
        customer.setBirthYear(now.getYear() - age);

        MainOrder order = new MainOrder();
//        order.setAmount(amount);
        order.setAmounts(amounts);
        order.setCustomer(customer);
        order.setInstallAddress(installAddress);
        order.setMortgageIdentifier(mortgageIdentifier);
        order.setOrderBy(who);
        order.setRecommendBy(recommendBy);
        order.setOrderTime(LocalDateTime.now());
//        order.setGood(good);
        order.makeRecord();

        queryDailySerialId(now, order);
        order.setOrderStatus(OrderStatus.forPay);
        return mainOrderRepository.saveAndFlush(order);
    }

    private synchronized void queryDailySerialId(LocalDate now, MainOrder order) {
        if (!dailySerials.containsKey(now)) {
            // 寻找当前库最大值
            final CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
            CriteriaQuery<Integer> max = criteriaBuilder.createQuery(Integer.class);
            Root<MainOrder> root = max.from(MainOrder.class);
            max = max.where(JpaFunctionUtils.dateEqual(criteriaBuilder, root.get("orderTime")
                    , now.toString()));
            max = max.select(criteriaBuilder.max(root.get("dailySerialId")));
            try {
                dailySerials.put(now, new AtomicInteger(entityManager.createQuery(max).getSingleResult()));
            } catch (Exception ignored) {
//                log.trace("", ignored);
                dailySerials.put(now, new AtomicInteger(0));
            }
        }

        order.setDailySerialId(dailySerials.get(now).incrementAndGet());
    }

    @Override
    public List<MainOrder> allOrders() {
        return mainOrderRepository.findAll();
    }

    @Override
    public MainOrder getOrder(long id) {
        return mainOrderRepository.getOne(id);
    }

    @Override
    public MainOrder getOrder(String orderId) {
        MainOrder order = mainOrderRepository.findOne((root, query, cb) -> orderIdPredicate(orderId, root, cb));
        if (order == null)
            throw new EntityNotFoundException();
        return order;
    }

    @Override
    public boolean isPaySuccess(long id) {
        return mainOrderRepository.getOne(id).isPay();
//        final CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
//        CriteriaQuery<Boolean> criteriaQuery = criteriaBuilder.createQuery(Boolean.class);
//        Root<MainOrder> root = criteriaQuery.from(MainOrder.class);
//        criteriaQuery = criteriaQuery.select(root.get("pay"));
//        criteriaQuery = criteriaQuery.where(criteriaBuilder.equal(root.get("id"), id));
//        return entityManager.createQuery(criteriaQuery).getSingleResult();
    }

    @Override
    public Login getEnjoyability(MainOrder order) {
        Login orderBy = order.getOrderBy();
        return getEnjoyability(orderBy);
    }

    @Override
    public Login getEnjoyability(Login orderBy) {
        Login login = orderBy;
        while (!loginService.isRegularLogin(login)) {
            // 最终都没有找到收益人 则给 管理员。。
            if (login == null)
                return loginService.byLoginName("root");
            login = login.getGuideUser();
        }
        return login;
    }

    @Override
    public Specification<MainOrder> search(String orderId, String mobile, Long goodId, LocalDate orderDate
            , OrderStatus status) {
        return (root, query, cb) -> {
            Predicate predicate = cb.isTrue(cb.literal(true));
            if (!StringUtils.isEmpty(orderId)) {
                log.debug("search order with orderId:" + orderId);
                //前面8位是 时间
                predicate = cb.and(predicate, orderIdPredicate(orderId, root, cb));
            } else if (orderDate != null) {
                log.debug("search order with orderDate:" + orderDate);
                predicate = cb.and(predicate, JpaFunctionUtils.dateEqual(cb, root.get("orderTime"), orderDate.toString()));
            }
            if (!StringUtils.isEmpty(mobile)) {
                log.debug("search order with mobile:" + mobile);
                // 2个都可以
                predicate = cb.and(predicate, cb.like(Customer.getMobile(MainOrder.getCustomer(root)), "%" + mobile + "%"));
            }
            if (goodId != null) {
                root.fetch(MainOrder_.amounts);
                predicate = cb.and(predicate, cb.equal(root.join(MainOrder_.amounts).key().get(MainGood_.id), goodId));
            }
            if (status != null && status != OrderStatus.EMPTY) {
                predicate = cb.and(predicate, cb.equal(root.get("orderStatus"), status));
            }

            return predicate;
        };
    }

    private Predicate orderIdPredicate(String orderId, Root<MainOrder> root, CriteriaBuilder cb) {
        String ymd = orderId.substring(0, 8);
        return cb.and(
                cb.equal(root.get("dailySerialId"), NumberUtils.parseNumber(orderId.substring(8), Integer.class))
                , JpaFunctionUtils.dateEqual(cb, root.get("orderTime")
                        , LocalDate.from(MainOrder.SerialDateTimeFormatter.parse(ymd)).toString())
        );
    }

    @Override
    public Specification<MainOrder> search(String search, OrderStatus status) {
        if (StringUtils.isEmpty(search) && (status == null || status == OrderStatus.EMPTY))
            return null;
        return (root, query, cb) -> {
            Predicate predicate = cb.isTrue(cb.literal(true));
            if (!StringUtils.isEmpty(search)) {
                log.debug("search order with mobile:" + search);
                // 2个都可以
                predicate = cb.and(predicate, cb.like(Customer.getMobile(MainOrder.getCustomer(root)), "%" + search + "%"));
            }

            if (status != null && status != OrderStatus.EMPTY) {
                predicate = cb.and(predicate, cb.equal(root.get("orderStatus"), status));
            }

            return predicate;
        };
    }

    @Override
    public void updateOrderTime(LocalDateTime time) {
        CriteriaUpdate<MainOrder> criteriaUpdate = entityManager.getCriteriaBuilder().createCriteriaUpdate(MainOrder.class);
        Root<MainOrder> root = criteriaUpdate.from(MainOrder.class);
        criteriaUpdate = criteriaUpdate.set(root.get("orderTime"), time);
        entityManager.createQuery(criteriaUpdate).executeUpdate();
    }

    @Override
    public List<Depot> depotsForOrder(long orderId) {
        MainOrder order = getOrder(orderId);
        // 库存多的优先
        return stockService.usableDepotFor((cb, root)
                -> cb.and(
                order.getAmounts().entrySet().stream()
                        .map(entry -> cb.and(
                                cb.equal(root.get(UsageStock_.product), entry.getKey().getProduct())
                                , cb.greaterThanOrEqualTo(root.get(UsageStock_.amount), entry.getValue())
                        ))
                        .toArray(Predicate[]::new)
        ));
//        final MainProduct product = order.getGood().getProduct();
//        return stockService.enabledUsableStockInfo(((productPath, criteriaBuilder)
//                -> criteriaBuilder.equal(productPath, product)), null)
//                .forProduct(product);
    }

    @Override
    public StockShiftUnit makeLogistics(Class<? extends LogisticsSupplier> supplierType, long orderId, long depotId
            , Map<MainProduct, Integer> amounts, boolean installation) throws StockOverrideException, UnnecessaryShipException {
        MainOrder order = getOrder(orderId);

        // 如果要发的 比需要发的多
        Map<MainProduct, Integer> require = order.getWantShipProduct();
        Map<MainProduct, Integer> toShip;
        if (amounts == null) {
            toShip = require;
        } else {
            for (MainProduct product : amounts.keySet()) {
                if (!require.containsKey(product))
                    throw new UnnecessaryShipException(product);
                if (require.get(product) - amounts.get(product) < 0)
                    throw new UnnecessaryShipException(product);
            }
            toShip = amounts;
        }


        Depot depot = depotRepository.getOne(depotId);

        LogisticsSupplier supplier;
        if (supplierType == HaierSupplier.class)
            supplier = haierSupplier;
        else
            supplier = applicationContext.getBean(supplierType);

        StockShiftUnit unit = logisticsService.makeShift(supplier, toShip.entrySet().stream()
                        .map((Function<Map.Entry<MainProduct, Integer>, Thing>) entry -> new Thing() {
                            @Override
                            public Product getProduct() {
                                return entry.getKey();
                            }

                            @Override
                            public ProductStatus getProductStatus() {
                                return ProductStatus.normal;
                            }

                            @Override
                            public int getAmount() {
                                return entry.getValue();
                            }
                        })
                        .collect(Collectors.toSet())
                , depot, order, installation ? LogisticsOptions.Installation : 0);

        if (order.getLogisticsSet() == null)
            order.setLogisticsSet(new ArrayList<>());

        order.getLogisticsSet().add(unit);
//        order.setCurrentLogistics(unit);
        if (order.getOrderStatus() == OrderStatus.forDeliver)
            order.setOrderStatus(OrderStatus.forDeliverConfirm);
        return unit;
    }

    @Override
    public StockShiftUnit makeLogistics(Class<? extends LogisticsSupplier> supplierType, long orderId, long depotId) {
        MainOrder order = getOrder(orderId);
        Depot depot = depotRepository.getOne(depotId);

        LogisticsSupplier supplier;
        if (supplierType == HaierSupplier.class)
            supplier = haierSupplier;
        else
            supplier = applicationContext.getBean(supplierType);

        StockShiftUnit unit = logisticsService.makeShift(supplier, order.getAmounts().entrySet().stream()
                        .map((Function<Map.Entry<MainGood, Integer>, Thing>) entry -> new Thing() {
                            @Override
                            public Product getProduct() {
                                return entry.getKey().getProduct();
                            }

                            @Override
                            public ProductStatus getProductStatus() {
                                return ProductStatus.normal;
                            }

                            @Override
                            public int getAmount() {
                                return entry.getValue();
                            }
                        })
                        .collect(Collectors.toSet())
                , depot, order, LogisticsOptions.Installation);

        if (order.getLogisticsSet() == null)
            order.setLogisticsSet(new ArrayList<>());

        order.getLogisticsSet().add(unit);
        order.setCurrentLogistics(unit);
        order.setOrderStatus(OrderStatus.forDeliverConfirm);
        return unit;
    }

    @Override
    public void forInstallationEvent(InstallationEvent event) {
        logisticsToMainOrder(event.getUnit(), order -> {
            final OrderStatus currentOrderStatus = order.getOrderStatus();
            logisticsInstalled(event, order, currentOrderStatus);
        });
    }

    @Override
    public void forShiftEvent(ShiftEvent event) {
        // 基于物流的变化，需要对订单进行状态更新
        // 只关注 拒绝事件
        final ShiftStatus toStatus = event.getStatus();
        if (toStatus != ShiftStatus.reject
                && toStatus != ShiftStatus.success)
            return;
        logisticsToMainOrder(event.getUnit(), order -> {
            final OrderStatus currentOrderStatus = order.getOrderStatus();
            switch (toStatus) {
                case reject:
                    logisticsReject(order, currentOrderStatus);
                    break;
                case success:
                    logisticsSuccess(event, order, currentOrderStatus);
                    break;
                default:
            }
        });

    }

    private void logisticsInstalled(InstallationEvent event, MainOrder order, OrderStatus currentOrderStatus) {
        // 怎么支持多个安装的物流信息呢？
        if (order.updateInstallationStatus(event.getUnit())) {
            applicationEventPublisher.publishEvent(new MainOrderFinishEvent(order, event));
        }
    }

    private void logisticsSuccess(ShiftEvent event, MainOrder order, OrderStatus currentOrderStatus) {
        if (order.updateLogisticsStatus()) {
            applicationEventPublisher.publishEvent(new MainOrderDeliveredEvent(order, event));
            if (order.updateInstallationStatus(null)) {
                applicationEventPublisher.publishEvent(new MainOrderFinishEvent(order, null));
            }
        }
    }

    /**
     * 如果所有物流都已失败，则切换为forDeliver；否者保留原有状态
     */
    private void logisticsReject(MainOrder order, OrderStatus currentOrderStatus) {
        order.updateLogisticsStatus();
    }

    private void logisticsToMainOrder(final StockShiftUnit unit, Consumer<MainOrder> consumer) {
        final CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<MainOrder> cq = cb.createQuery(MainOrder.class);
        Root<MainOrder> root = cq.from(MainOrder.class);
        try {
            MainOrder order = entityManager.createQuery(cq
                    .where(cb.isMember(unit, root.get("logisticsSet")))
            )
                    .getSingleResult();
            consumer.accept(order);
        } catch (NoResultException ignored) {
            log.error("居然没有这个订单！我们还做别的生意么?" + unit.getId(), ignored);
        }
    }
}
