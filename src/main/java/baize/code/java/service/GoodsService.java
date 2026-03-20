package baize.code.java.service;

import baize.code.java.common.Result;
import com.baomidou.mybatisplus.extension.service.IService;
import baize.code.java.entity.Goods;

public interface GoodsService extends IService<Goods> {
    int add(Goods goods);


    Boolean update(Goods goods);

    Goods detailById(Integer id);

    /**
     * 根据id删除商品
     * @param id
     * @return
     */
    Result<?> delete(Integer id);
}