package team.compass.photo.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import team.compass.post.domain.PostPhoto;

import java.util.List;


public interface PostPhotoRepository extends JpaRepository<PostPhoto, Integer> {
    @Query("select pp from PostPhoto pp left join pp.photo where pp.post.id = :id")
    List<PostPhoto> findListById(@Param(value = "id") Integer id); // 단건 조회 post <-> photo 연결
}
