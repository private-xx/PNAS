package com.pnas.server.backup;

import com.pnas.common.error.ErrorCode;
import com.pnas.server.auth.SessionPrincipal;
import com.pnas.server.backup.domain.Device;
import com.pnas.server.backup.domain.DevicePlatform;
import com.pnas.server.common.error.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    public record CreateDevice(String name, String platform) {}
    public record DeviceDto(UUID id, String name, String platform, Instant createdAt,
                            Instant lastSeenAt, boolean revoked) {}
    public record CreatedDto(DeviceDto device, String token) {}

    private final DeviceService devices;

    public DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @PostMapping
    public CreatedDto create(@AuthenticationPrincipal SessionPrincipal me, @RequestBody CreateDevice req) {
        DevicePlatform platform = parsePlatform(req.platform());
        var created = devices.create(me, req.name(), platform);
        return new CreatedDto(toDto(created.device()), created.token());
    }

    @GetMapping
    public List<DeviceDto> list(@AuthenticationPrincipal SessionPrincipal me) {
        return devices.list(me).stream().map(this::toDto).toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal SessionPrincipal me, @PathVariable UUID id) {
        devices.revoke(me, id);
    }

    private DevicePlatform parsePlatform(String raw) {
        try {
            return DevicePlatform.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST,
                "platform 仅支持 MAC/WINDOWS/OTHER");
        }
    }

    private DeviceDto toDto(Device d) {
        return new DeviceDto(d.getId(), d.getName(), d.getPlatform().name(), d.getCreatedAt(),
            d.getLastSeenAt(), d.isRevoked());
    }
}
