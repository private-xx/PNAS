package com.pnas.server.backup;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.backup.domain.Device;
import com.pnas.server.backup.domain.DevicePlatform;
import com.pnas.server.blobstore.Sha256;
import com.pnas.server.common.error.BusinessException;
import com.pnas.server.iam.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeviceService {

    public record Created(Device device, String token) {}

    private final DeviceRepository devices;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    public DeviceService(DeviceRepository devices, UserRepository users) {
        this.devices = devices;
        this.users = users;
    }

    @Transactional
    public Created create(SessionPrincipal me, String name, DevicePlatform platform) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "设备名不能为空");
        }
        var user = users.findById(me.userId()).orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "用户不存在"));
        String token = randomToken();
        Device device = devices.save(Device.create(user, name.trim(), platform,
            Sha256.hex(token.getBytes(StandardCharsets.UTF_8))));
        return new Created(device, token);
    }

    @Transactional(readOnly = true)
    public List<Device> list(SessionPrincipal me) {
        return devices.findByUserIdOrderByCreatedAtDesc(me.userId());
    }

    @Transactional
    public void revoke(SessionPrincipal me, UUID deviceId) {
        Device device = devices.findByIdAndUserId(deviceId, me.userId())
            .orElseThrow(this::notFound);
        if (!device.isRevoked()) {
            device.markRevoked();
            devices.save(device);
        }
    }

    @Transactional(readOnly = true)
    public Optional<Device> resolveToken(String rawToken) {
        return devices.findByPatHashAndRevokedAtIsNull(
            Sha256.hex(rawToken.getBytes(StandardCharsets.UTF_8)));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "设备不存在");
    }
}
