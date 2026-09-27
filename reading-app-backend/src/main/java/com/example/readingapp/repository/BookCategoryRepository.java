package com.example.readingapp.repository;

import com.example.readingapp.entity.BookCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookCategoryRepository extends JpaRepository<BookCategory, Long> {

    /** 全部分类（先主分类后子分类的顺序由调用方组合，这里只保证同级稳定有序） */
    List<BookCategory> findAllByOrderBySortOrderAscIdAsc();

    /** 所有主分类 */
    List<BookCategory> findByParentIdIsNullOrderBySortOrderAscIdAsc();

    /** 某个主分类下的子分类 */
    List<BookCategory> findByParentIdOrderBySortOrderAscIdAsc(Long parentId);

    /**
     * 同父同名查重。注意 parentId 为 null 时 Spring Data 生成的是 {@code parent_id = null}
     * （永不成立），所以主分类必须用下面那个 IsNull 版本，别用这个传 null。
     */
    boolean existsByNameAndParentId(String name, Long parentId);

    /** 主分类查重 */
    boolean existsByNameAndParentIdIsNull(String name);

    /** 某个主分类（不带排序，删除级联时用） */
    List<BookCategory> findByParentId(Long parentId);

    /** 删除某主分类下的全部子分类（分类删除的级联；调用方必须在事务内） */
    void deleteByParentId(Long parentId);
}
