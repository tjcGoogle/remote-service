package com.bestv.remote;

public class Test {

    public static void main(String[] args) throws InterruptedException {
//        System.out.println("ok");
//        RestTemplateHandler restTemplateHandler = new RestTemplateHandler();
//        ServerContext serverContext = new ServerContext();
//        serverContext.setConnectTimeout(300);
//        serverContext.setReadTimeout(400);
//        serverContext.setKeepAliveDuration(15 * 1000);
//        serverContext.setMaxIdleConnections(100);
//        restTemplateHandler.init(serverContext);
//        RestTemplate restTemplate = restTemplateHandler.createOkHttp3RestTemplate();
//        CountDownLatch countDownLatch = new CountDownLatch(1000 * 100);
//        StopWatch stopWatch = new StopWatch();
//        stopWatch.start();
//        ExecutorService executorService = Executors.newFixedThreadPool(10);
//        for (int i = 0; i < 1000; i++) {
//            executorService.execute(() -> {
//                for (int i1 = 0; i1 < 100; i1++) {
//                    try {
//                        HttpHeaders httpHeaders = new HttpHeaders();
//                        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
//                        HttpEntity<Object> httpEntity = new HttpEntity<>(new HashMap<>(), httpHeaders);
//                        ResponseEntity<String> exchange = restTemplate.exchange("http://localhost:6543/v2/recommend/topicdetail", HttpMethod.POST, httpEntity, String.class);
//                        System.out.println(countDownLatch.getCount());
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                    } finally {
//                        countDownLatch.countDown();
//                    }
//                }
//            });
//        }
//        countDownLatch.await();
//        stopWatch.stop();
//        System.out.println(stopWatch.getTotalTimeMillis());
    }
}
