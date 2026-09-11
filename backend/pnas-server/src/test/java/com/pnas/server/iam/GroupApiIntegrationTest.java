package com.pnas.server.iam;

import com.jayway.jsonpath.JsonPath;
import com.pnas.server.AbstractIntegrationTest;
import com.pnas.server.files.FilesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 组管理接口(FR-AUTH-05):仅 ADMIN 可创建组与维护成员。 */
@AutoConfigureMockMvc
class GroupApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired FilesService files;
    @Autowired AclService acl;

    private record Auth(String cookie, String csrf) {}

    private Auth login(String username, String password) throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return new Auth(res.getResponse().getHeader("Set-Cookie").split(";")[0],
            res.getResponse().getHeader("X-CSRF-Token"));
    }

    @Test
    void adminCreatesGroupAndMaintainsMembers() throws Exception {
        var admin = login("admin", "admin-secret");

        String member = "grp-member-" + UUID.randomUUID().toString().substring(0, 8);
        String created = mvc.perform(post("/api/v1/users")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + member + "\",\"displayName\":\"M\",\"password\":\"secret123\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String memberId = JsonPath.read(created, "$.id");

        String groupName = "家庭组-" + UUID.randomUUID().toString().substring(0, 6);
        String group = mvc.perform(post("/api/v1/groups")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + groupName + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value(groupName))
            .andReturn().getResponse().getContentAsString();
        String groupId = JsonPath.read(group, "$.id");

        // 加成员 → 列表可见
        mvc.perform(post("/api/v1/groups/{id}/members", groupId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + memberId + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.members[0].username").value(member));

        String list = mvc.perform(get("/api/v1/groups").header("Cookie", admin.cookie()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(list).contains(groupId).contains(member);

        // 移除成员
        mvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, memberId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.members").isEmpty());
    }

    @Test
    void renameAndDeleteGroupAlsoCleansAclEntries() throws Exception {
        var admin = login("admin", "admin-secret");
        var adminUser = users.findByUsername("admin").orElseThrow();
        var home = files.ensureUserHome(adminUser);

        String groupName = "待删组-" + UUID.randomUUID().toString().substring(0, 6);
        String created = mvc.perform(post("/api/v1/groups")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + groupName + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        UUID groupId = UUID.fromString(JsonPath.read(created, "$.id"));

        // 给该组一条 ACL 授权,验证删除组时不会留下悬挂授权
        var principal = new com.pnas.server.auth.SessionPrincipal(
            adminUser.getId(), adminUser.getUsername(), adminUser.getRole(), UUID.randomUUID());
        acl.setAcl(principal, home.getId(), java.util.List.of(
            new com.pnas.server.iam.AclService.EntryDto("GROUP", groupId, "r", true)));
        assertThat(acl.listAcl(principal, home.getId())).hasSize(1);

        // 重命名
        String renamed = "已改名-" + UUID.randomUUID().toString().substring(0, 6);
        mvc.perform(patch("/api/v1/groups/{id}", groupId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + renamed + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value(renamed));

        // 删除 → ACL 条目一并清理
        mvc.perform(delete("/api/v1/groups/{id}", groupId)
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf()))
            .andExpect(status().isNoContent());
        assertThat(acl.listAcl(principal, home.getId())).isEmpty();
    }

    @Test
    void nonAdminCannotManageGroups() throws Exception {
        var admin = login("admin", "admin-secret");
        String member = "grp-bob-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/users")
                .header("Cookie", admin.cookie()).header("X-CSRF", admin.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + member + "\",\"displayName\":\"B\",\"password\":\"secret123\"}"))
            .andExpect(status().isOk());
        var bob = login(member, "secret123");

        mvc.perform(post("/api/v1/groups")
                .header("Cookie", bob.cookie()).header("X-CSRF", bob.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"越权组\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/groups").header("Cookie", bob.cookie()))
            .andExpect(status().isForbidden());
    }
}
