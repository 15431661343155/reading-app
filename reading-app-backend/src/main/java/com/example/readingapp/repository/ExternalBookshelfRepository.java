package com.example.readingapp.repository;

import com.example.readingapp.entity.ExternalBookshelf;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExternalBookshelfRepository extends JpaRepository<ExternalBookshelf, Long> {

    Optional<ExternalBookshelf> findByUserIdAndSourceTypeAndSourceBookId(
            Long userId, String sourceType, String sourceBookId);

    List<ExternalBookshelf> findAllByUserId(Long userId);

    /** 派生删除方法必须带事务，否则报 "No EntityManager with actual transaction available"。 */
    @Transactional
    void deleteByUserIdAndSourceTypeAndSourceBookId(
            Long userId, String sourceType, String sourceBookId);

    /** 删除某用户的全部外站书架记录（删除用户时级联清理）。 */
    @Transactional
    void deleteByUserId(Long userId);
}
