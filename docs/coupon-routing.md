# 쿠폰 API 경로

GATEWAY_COUPON_ENABLED는 기본 false입니다. CouponService와 Account 권한 설정을 준비한 뒤 별도 승인으로 켭니다. /api/v1/coupons 및 하위 경로와 /api/v1/admin/coupons/**만 coupon-service로 전달합니다. GET /api/v1/coupons/campaigns/{code}만 익명 허용하고 나머지 사용자 경로는 BUYER, 관리 경로는 ADMIN을 요구합니다. Cookie와 Origin은 제거하고 JWT는 유지합니다. 쿠폰 쓰기 요청에는 재시도를 붙이지 않으며 내부 API는 라우팅하지 않습니다.

되돌릴 때 플래그를 false로 하고 CouponService의 기존 잠금 복구는 유지합니다. 운영 배포와 SSM 등록은 이 변경에 포함하지 않습니다.
