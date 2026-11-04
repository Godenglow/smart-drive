package com.smartdrive.driver.service;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.driver.dto.LocationRequest;
import com.smartdrive.driver.dto.DriverStateView;
import com.smartdrive.driver.dto.OnlineView;
import com.smartdrive.driver.dto.ProfileView;
import com.smartdrive.driver.mapper.DriverProfileMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class DriverService {
    public static final String GEO_KEY = "smartdrive:driver:geo:online";
    public static final String ONLINE_PREFIX = "smartdrive:driver:online:";
    private static final DefaultRedisScript<Long> ONLINE = script("redis.call('GEOADD', KEYS[1], ARGV[2], ARGV[3], ARGV[1]); "
            + "redis.call('SET', KEYS[2], '1', 'EX', 120); return 1");
    private static final DefaultRedisScript<Long> LOCATION = script("if redis.call('EXISTS', KEYS[2]) == 0 then return 0 end; "
            + "redis.call('GEOADD', KEYS[1], ARGV[2], ARGV[3], ARGV[1]); "
            + "redis.call('SET', KEYS[2], '1', 'EX', 120); return 1");
    private static final DefaultRedisScript<Long> OFFLINE = script("redis.call('DEL', KEYS[2]); "
            + "redis.call('ZREM', KEYS[1], ARGV[1]); return 1");
    private static final DefaultRedisScript<List> NEARBY = nearbyScript();

    private final DriverProfileMapper profiles;
    private final StringRedisTemplate redis;

    public DriverService(DriverProfileMapper profiles, StringRedisTemplate redis) {
        this.profiles = profiles;
        this.redis = redis;
    }

    public ProfileView saveProfile(long userId, String name) {
        profiles.upsert(userId, name.trim());
        return profiles.find(userId);
    }

    public DriverStateView state(long userId) {
        return new DriverStateView(profiles.find(userId), Boolean.TRUE.equals(redis.hasKey(ONLINE_PREFIX + userId)));
    }

    public OnlineView online(long userId, LocationRequest location) {
        requireProfile(userId);
        run(ONLINE, userId, location);
        return new OnlineView(true, Instant.now());
    }

    public OnlineView updateLocation(long userId, LocationRequest location) {
        requireProfile(userId);
        if (run(LOCATION, userId, location) == 0L) {
            throw new ApiException(ErrorCode.DRIVER_OFFLINE);
        }
        return new OnlineView(true, Instant.now());
    }

    public OnlineView offline(long userId) {
        run(OFFLINE, userId, null);
        return new OnlineView(false, Instant.now());
    }

    public List<Long> nearby(LocationRequest location) {
        List<?> members = redis.execute(NEARBY, List.of(GEO_KEY),
                location.lng().toPlainString(), location.lat().toPlainString());
        if (members == null) throw new IllegalStateException("Redis nearby query returned no result");
        return members.stream().map(member -> Long.parseLong(member.toString())).toList();
    }

    private void requireProfile(long userId) {
        if (profiles.find(userId) == null) throw new ApiException(ErrorCode.NOT_FOUND, "请先创建司机资料");
    }

    private long run(DefaultRedisScript<Long> script, long userId, LocationRequest location) {
        String id = Long.toString(userId);
        Long result = redis.execute(script, List.of(GEO_KEY, ONLINE_PREFIX + id), id,
                location == null ? "" : location.lng().toPlainString(),
                location == null ? "" : location.lat().toPlainString());
        if (result == null) throw new IllegalStateException("Redis script returned no result");
        return result;
    }

    private static DefaultRedisScript<Long> script(String source) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(Long.class);
        return script;
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> nearbyScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText("local members = redis.call('GEOSEARCH', KEYS[1], "
                + "'FROMLONLAT', ARGV[1], ARGV[2], 'BYRADIUS', 5, 'km', 'ASC'); "
                + "local result = {}; "
                + "for _, id in ipairs(members) do "
                + "if redis.call('EXISTS', 'smartdrive:driver:online:' .. id) == 1 then "
                + "table.insert(result, id); if #result == 5 then break end "
                + "else redis.call('ZREM', KEYS[1], id) end end; return result");
        script.setResultType(List.class);
        return script;
    }
}
