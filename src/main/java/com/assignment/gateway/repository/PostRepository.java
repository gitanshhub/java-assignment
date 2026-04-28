package com.assignment.gateway.repository;

import com.assignment.gateway.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostRepository extends JpaRepository<Post, Long> {
}
