package baize.code.java.service;

import com.baomidou.mybatisplus.extension.service.IService;
import baize.code.java.common.Result;
import baize.code.java.entity.Role;

public interface RoleService extends IService<Role> {
    Result<?> add(Role role);

    Result<?> delete(Integer id);

    Result<?> update(Role role);

    Result<?> detailsByCtId(Integer ctId);

    /**
     * 根据商户id查询客服
     * @param ctId
     * @return
     */
    Role getRoleById(Integer ctId);
    
}