package team.compass.post.service;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import team.compass.common.config.JwtTokenProvider;
import team.compass.comment.repository.CommentRepository;
import team.compass.like.repository.LikeRepository;
import team.compass.photo.domain.Photo;
import team.compass.photo.repository.PhotoRepository;
import team.compass.photo.repository.PostPhotoRepository;
import team.compass.photo.service.FileUploadService;
import team.compass.post.controller.response.PostResponse;
import team.compass.post.domain.Post;
import team.compass.post.domain.PostPhoto;
import team.compass.post.dto.PhotoDto;
import team.compass.post.dto.PostDto;
import team.compass.post.repository.PostCustomRepository;
import team.compass.post.repository.PostRepository;
import team.compass.theme.domain.Theme;
import team.compass.theme.repository.ThemeRepository;
import team.compass.user.domain.User;
import team.compass.user.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {


    private final PostRepository postRepository;


    private final FileUploadService fileUploadService;


    private final PhotoRepository photoRepository;

    private final PostPhotoRepository postPhotoRepository;

    private final ThemeRepository themeRepository;

    private final PostCustomRepository postCustomRepository;

    private final CommentRepository commentRepository;




    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    private final LikeRepository likeRepository;


    /**
     * 글 작성 (사진 포함 같이)
     * <p>
     * 1. post 저장
     * 2. S3 로직 (사진 API), 사진 저장
     * 순서 : 글 -> 사진 -> postPhoto
     */
    @Override
    @Transactional
    public Post write(Post createPost, List<MultipartFile> multipartFile, User user) {
        Theme theme = themeRepository.findById(createPost.getTheme().getId()) // 테마 id 찾기
                .orElseThrow(() -> new IllegalStateException("없는 테마입니다."));
        createPost.setTheme(theme); // flush 오류 -> 영속성 컨텍스트에 없어서 불러오기 // 받아온 테마 id 넣어주기
        Post post = postRepository.save(createPost); // 글 저장  == Post 저장 // 그 결과값들 postRepo 저장해서 다시 post로 묶어주기
        List<PostPhoto> list = savePhotos(multipartFile, user, post); // 마지막으로 postPhoto에 리스트로 받아오기
        postPhotoRepository.saveAll(list); // 받아온 데이터들 postPhotoRepo 저장
        return post;
    }

    /**
     * 글 업데이트(기존 사진 삭제 -> 새 사진 업로드)
     */
    @Override
    @Transactional
    public Post update(Post updatePost, List<MultipartFile> multipartFile, User user, Integer postId) {
        // post 업데이트
        Post udPost = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("해당 게시글이 없습니다."));

        if (!udPost.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException(" 권한없음 ");
        }
        udPost.setTitle(updatePost.getTitle()); // 제목
        udPost.setLocation(updatePost.getLocation()); // 장소
        udPost.setDetail(updatePost.getDetail()); // 내용
        udPost.setStartDate(updatePost.getStartDate()); // 여행 시작
        udPost.setEndDate(updatePost.getEndDate()); // 여행 끝
        udPost.setHashtag(updatePost.getHashtag()); // 해시태그
        udPost.setTheme(updatePost.getTheme()); //
        udPost.setUser(user);

        postRepository.save(udPost); // 업데이트로 쓰인 데이터들 repo 저장
        //사진 저장
        List<PostPhoto> list = savePhotos(multipartFile, user, udPost);
        //이전의 사진데이터 삭제 -> 기준이 없기에
        List<PostPhoto> photos = udPost.getPhotos();
        postPhotoRepository.deleteAll(photos);
        //새로운 사진저장
        postPhotoRepository.saveAll(list);
        return udPost;
    }

    /**
     * 글 삭제
     * Post entity photos 부분 -> cascade = CascadeType.REMOVE
     * 함으로써 Post 삭제시 photos 도 삭제됨.
     * 처리 안 할 경우에 null 이 돼서 fk 가 보는 곳이 없어짐. 아니면 null 처리가 따로 있다던지..
     */
    @Override
    @Transactional
    public boolean delete(Integer postId, User user) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("해당 게시글이 없습니다."));
        if (!post.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException(" 권한없음 ");
        }
        postRepository.deleteById(postId); // 삭제
        return true;
    }

    /**
     * 사진 저장
     */
    private List<PostPhoto> savePhotos(List<MultipartFile> multipartFile, User user, Post post) {
        List<PostPhoto> list = new ArrayList<>(); // 사진 저장 리스트
        for (MultipartFile file : multipartFile) {
            PhotoDto photoDto = fileUploadService.save(file); // 하나씩 리스트로 저장
            Photo photo = photoRepository.save(photoDto.toEntity(user));
            list.add(new PostPhoto(post, photo)); // PostPhoto, Photo 저장
        }
        return list;
    }

    /**
     * 해당 글 가져오기
     */
    @Override
    @Transactional(readOnly = true)
    public PostResponse getPost(Integer postId) {
        Post post = postCustomRepository.findWithLikeById(postId)
                .orElseThrow(() -> new IllegalArgumentException("해당 게시글이 없습니다."));
        Integer themeId = post.getTheme().getId();
        Long commentCount = commentRepository.countByPostId(postId);
        return new PostResponse(post, commentCount, themeId);
    }


    /**
     * <해당 테마 글 리스트 조회>
     * fetch join 에는 limit 을 지원하지 않아서 따로 custom repository를 이용하여 sql 문으로 작성해 이용하기
     * fetch join 을 이용하고 할 경우, likes 수로 인해 raw 가 1개의 글 - 1raw 가 아닌 1개의 글 - 3raw (3개 좋아요) 이런 식으로 나오게 됐다.
     * 그렇게 될 경우, 10개만 뽑아서 리스트를 가져오고 싶은데 위의 상황으로 인해 2개, 3개,... 이런 식으로밖에 못가져옴.
     * 그래서 해당 sql 문으로 작성과 properties 에 batch size를 걸어 해당 수만큼 한번에 묶음 형식으로 가져오도록 설정해놨더니 바라던 결과대로 나옴.
     */
    @Override
    @Transactional
    public List<PostDto> themePageSelect(Integer themeId, Integer lastId) {
        List<Post> postList = postCustomRepository.findByTheme(themeId, lastId); // themeId, LastId 추려서 post 가져오기 (이때 post select 1번)
        return postList.stream().map(post -> new PostDto( // 📌멘토님의 제안안 stream 으로 반환하기. o
                post.getId(),
                // like
                post.getLikes().size(),
                // photo list 형식이니 stream
                post.getPhotos().stream().map(i -> i.getPhoto().getStoreFileUrl()).collect(Collectors.toList()),
                post.getTitle(),
                post.getLocation(),
                post.getStartDate(),
                post.getEndDate())).collect(Collectors.toList());
    }
}