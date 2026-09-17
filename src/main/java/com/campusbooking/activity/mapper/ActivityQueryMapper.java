package com.campusbooking.activity.mapper;

import com.campusbooking.activity.dto.ActivityViews.Summary;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ActivityQueryMapper {
    String FILTER = """
            FROM activity a JOIN activity_location l ON l.id = a.location_id
            WHERE EXISTS (SELECT 1 FROM activity_session s WHERE s.activity_id = a.id AND s.status = 'PUBLISHED')
            <if test="locations != null">
              AND a.location_id IN
              <foreach collection="locations" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            """;

    @Select("<script>SELECT COUNT(*) " + FILTER + "</script>")
    long countVisible(@Param("locations") List<Long> locations);

    @Select("""
            <script>
            SELECT a.id, a.title, a.cover_image, a.location_id, l.name AS location_name, NULL AS distance_km
            """ + FILTER + """
            <choose>
              <when test="locations != null">
                ORDER BY FIELD(a.location_id,
                <foreach collection="locations" item="id" separator=",">#{id}</foreach>), a.id DESC
              </when>
              <otherwise>ORDER BY a.id DESC</otherwise>
            </choose>
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<Summary> listVisible(@Param("locations") List<Long> locations,
                              @Param("offset") long offset, @Param("size") int size);
}
