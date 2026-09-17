package com.campusbooking.activity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusbooking.account.auth.UserContext;
import com.campusbooking.account.model.Role;
import com.campusbooking.activity.dto.ActivityRequests.*;
import com.campusbooking.activity.dto.ActivityViews.*;
import com.campusbooking.activity.mapper.*;
import com.campusbooking.activity.model.*;
import com.campusbooking.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class ActivityService {
    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);
    private final ActivityMapper activities;
    private final LocationMapper locations;
    private final SessionMapper sessions;
    private final ActivityQueryMapper queries;
    private final ActivityDetailCache cache;
    private final SessionRuntimeStore runtime;
    private final ActivityGeoStore geo;
    private final ActivityProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ActivityService(ActivityMapper activities, LocationMapper locations, SessionMapper sessions,
                           ActivityQueryMapper queries, ActivityDetailCache cache, SessionRuntimeStore runtime,
                           ActivityGeoStore geo, ActivityProperties properties, Clock clock,
                           PlatformTransactionManager transactionManager) {
        this.activities = activities;
        this.locations = locations;
        this.sessions = sessions;
        this.queries = queries;
        this.cache = cache;
        this.runtime = runtime;
        this.geo = geo;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(10);
    }

    public Location createLocation(LocationCreate request) {
        Location location = new Location();
        location.setOrganizerId(requireOrganizer());
        location.setName(request.name().strip());
        location.setAddress(request.address().strip());
        location.setLongitude(request.longitude());
        location.setLatitude(request.latitude());
        locations.insert(location);
        // 地点不可修改；有场次发布时才建立 GEO 索引。
        return location;
    }

    public Page<Location> locations(int page, int size) {
        validatePage(page, size);
        long total = locations.selectCount(null);
        var items = locations.selectList(new LambdaQueryWrapper<Location>().orderByDesc(Location::getId)
                .last("LIMIT " + size + " OFFSET " + ((long) (page - 1) * size)));
        return new Page<>(page, size, total, items);
    }

    public Activity create(ActivityCreate request) {
        long userId = requireOrganizer();
        if (locations.selectById(request.locationId()) == null) throw notFound();
        Activity activity = new Activity();
        activity.setOrganizerId(userId);
        activity.setLocationId(request.locationId());
        activity.setTitle(request.title().strip());
        activity.setDescription(request.description().strip());
        activity.setCoverImage(request.coverImage());
        activities.insert(activity);
        return activity;
    }

    public void updateDisplay(long id, DisplayUpdate request) {
        long userId = requireOrganizer();
        change(id, () -> {
            Activity activity = owned(id, userId, true);
            activity.setTitle(request.title().strip());
            activity.setDescription(request.description().strip());
            activity.setCoverImage(request.coverImage());
            // updateById 默认忽略 null；使用显式 SET 允许清空封面。
            activities.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Activity>()
                    .eq(Activity::getId, id).set(Activity::getTitle, activity.getTitle())
                    .set(Activity::getDescription, activity.getDescription()).set(Activity::getCoverImage, request.coverImage()));
            return null;
        });
    }

    public SessionView createSession(long activityId, SessionWrite request) {
        long userId = requireOrganizer();
        return change(activityId, () -> {
            owned(activityId, userId, true);
            ActivitySession session = new ActivitySession();
            session.setActivityId(activityId);
            session.setStatus("DRAFT");
            applySession(session, request);
            sessions.insert(session);
            return view(session);
        });
    }

    public SessionView editSession(long activityId, long sessionId, SessionWrite request) {
        long userId = requireOrganizer();
        return change(activityId, () -> {
            owned(activityId, userId, true);
            ActivitySession session = session(activityId, sessionId);
            if (!"DRAFT".equals(session.getStatus()))
                throw new ApiException(HttpStatus.CONFLICT, "SESSION_FROZEN", "已发布场次的容量和时间不可修改");
            applySession(session, request);
            sessions.updateById(session);
            return view(session);
        });
    }

    public PublishResult publish(long activityId, long sessionId) {
        long userId = requireOrganizer();
        return cache.withLock(activityId, () -> {
            // execute 返回时 MySQL 已提交；Redis 同步失败不回滚发布事实。
            ActivitySession saved = transaction.execute(status -> {
                owned(activityId, userId, true);
                ActivitySession session = session(activityId, sessionId);
                if ("DRAFT".equals(session.getStatus())) {
                    if (!session.getRegistrationStartAt().isAfter(clock.instant()))
                        throw new ApiException(HttpStatus.CONFLICT, "REGISTRATION_ALREADY_OPEN",
                                "报名开始时间必须晚于发布操作，请先修改草稿");
                    session.setStatus("PUBLISHED");
                    session.setRuntimeExpireAt(session.getEndAt().plus(properties.runtimeBuffer())
                            .truncatedTo(ChronoUnit.MILLIS));
                    sessions.updateById(session);
                }
                return session;
            });
            boolean runtimeReady = false;
            boolean geoReady = false;
            try {
                runtimeReady = runtime.initialize(Objects.requireNonNull(saved));
                if (!runtimeReady) log.warn("Session runtime conflict or unsafe initialization sessionId={}", sessionId);
            } catch (RuntimeException exception) {
                log.warn("Session runtime synchronization incomplete sessionId={} reason={}",
                        sessionId, exception.getClass().getSimpleName());
            }
            try {
                Activity activity = activities.selectById(activityId);
                geo.index(locations.selectById(activity.getLocationId()));
                geoReady = true;
            } catch (RuntimeException exception) {
                log.warn("Activity GEO synchronization incomplete activityId={} reason={}",
                        activityId, exception.getClass().getSimpleName());
            }
            boolean displayReady = true;
            try { invalidateCommitted(activityId); }
            catch (ApiException exception) { displayReady = false; }
            String message = !runtimeReady ? "配置已保存，报名运行配置同步未完成，暂不可报名"
                    : !geoReady ? "报名运行配置已就绪，附近活动索引同步未完成"
                    : "发布完成，报名运行配置已就绪；按报名窗口受理";
            if (!displayReady) message += "；展示缓存失效未完成，旧内容可能保留至过期";
            return new PublishResult(sessionId, "PUBLISHED", runtimeReady, geoReady, displayReady, message);
        });
    }

    public Detail ownerDetail(long id) {
        Activity activity = owned(id, requireOrganizer(), false);
        return detail(activity, false);
    }

    public Detail detail(long id) {
        Detail result = cache.get(id, () -> {
            Activity activity = activities.selectById(id);
            if (activity == null) return null;
            Detail value = detail(activity, true);
            return value.sessions().isEmpty() ? null : value;
        });
        if (result == null) throw notFound();
        return result;
    }

    public Page<Summary> list(int page, int size) {
        validatePage(page, size);
        return new Page<>(page, size, queries.countVisible(null),
                queries.listVisible(null, (long) (page - 1) * size, size));
    }

    public Page<Summary> nearby(double longitude, double latitude, double radiusKm, int page, int size) {
        validatePage(page, size);
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude) || !Double.isFinite(radiusKm)
                || longitude < -180 || longitude > 180 || latitude < -85.05112878 || latitude > 85.05112878
                || radiusKm <= 0 || radiusKm > 50)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "经纬度不合法，或半径不在 (0,50] 公里内");
        var distances = geo.nearby(longitude, latitude, radiusKm);
        if (distances.isEmpty()) return new Page<>(page, size, 0, List.of());
        // 先得到半径内地点，再在 MySQL 过滤已发布活动并分页，避免先截断地点导致漏页。
        List<Long> ids = List.copyOf(distances.keySet());
        var items = queries.listVisible(ids, (long) (page - 1) * size, size).stream()
                .map(item -> new Summary(item.id(), item.title(), item.coverImage(), item.locationId(),
                        item.locationName(), distances.get(item.locationId()))).toList();
        return new Page<>(page, size, queries.countVisible(ids), items);
    }

    private <T> T change(long id, Supplier<T> action) {
        return cache.withLock(id, () -> {
            T result = transaction.execute(status -> action.get());
            invalidateCommitted(id);
            return result;
        });
    }

    private void invalidateCommitted(long id) {
        try { cache.invalidate(id); }
        catch (RuntimeException exception) {
            log.warn("Activity committed but cache invalidation failed activityId={} reason={}",
                    id, exception.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DISPLAY_SYNC_INCOMPLETE",
                    "数据库已保存，展示缓存失效未完成，旧内容可能保留至缓存过期");
        }
    }

    private Activity owned(long id, long userId, boolean lock) {
        Activity activity = lock ? activities.lockById(id) : activities.selectById(id);
        if (activity == null) throw notFound();
        if (!activity.getOrganizerId().equals(userId))
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ACTIVITY_OWNER", "只能管理自己创建的活动");
        return activity;
    }

    private ActivitySession session(long activityId, long sessionId) {
        ActivitySession session = sessions.selectById(sessionId);
        if (session == null || !session.getActivityId().equals(activityId)) throw notFound();
        return session;
    }

    private void applySession(ActivitySession session, SessionWrite request) {
        Instant opens = request.registrationStartAt().truncatedTo(ChronoUnit.MILLIS);
        Instant closes = request.registrationEndAt().truncatedTo(ChronoUnit.MILLIS);
        Instant starts = request.startAt().truncatedTo(ChronoUnit.MILLIS);
        Instant ends = request.endAt().truncatedTo(ChronoUnit.MILLIS);
        if (!opens.isBefore(closes) || closes.isAfter(starts) || !starts.isBefore(ends)
                || !opens.isAfter(clock.instant()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SESSION_TIME",
                    "要求：当前时间 < 报名开始 < 报名结束 <= 场次开始 < 场次结束");
        session.setCapacity(request.capacity());
        session.setRemainingCapacity(request.capacity());
        session.setRegistrationStartAt(opens);
        session.setRegistrationEndAt(closes);
        session.setStartAt(starts);
        session.setEndAt(ends);
    }

    private Detail detail(Activity activity, boolean publishedOnly) {
        var query = new LambdaQueryWrapper<ActivitySession>().eq(ActivitySession::getActivityId, activity.getId())
                .eq(publishedOnly, ActivitySession::getStatus, "PUBLISHED")
                .orderByAsc(ActivitySession::getStartAt).orderByAsc(ActivitySession::getId);
        return new Detail(activity.getId(), activity.getOrganizerId(), activity.getTitle(),
                activity.getDescription(), activity.getCoverImage(), locations.selectById(activity.getLocationId()),
                sessions.selectList(query).stream().map(ActivityService::view).toList());
    }

    private static SessionView view(ActivitySession session) {
        return new SessionView(session.getId(), session.getCapacity(), session.getRegistrationStartAt(),
                session.getRegistrationEndAt(), session.getStartAt(), session.getEndAt(), session.getStatus());
    }

    private static long requireOrganizer() {
        var user = UserContext.require();
        if (user.role() != Role.ORGANIZER)
            throw new ApiException(HttpStatus.FORBIDDEN, "ORGANIZER_REQUIRED", "此操作需要组织者角色");
        return user.userId();
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 50)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "page 必须大于零，size 必须在 1 到 50 之间");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "地点、活动或场次不存在或未发布");
    }
}
