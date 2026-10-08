FROM nginx@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94
COPY frontend/out /srv/web
COPY deploy/frontend/nginx.conf.template /etc/nginx/nginx.conf.template
COPY deploy/frontend/proxy.conf /etc/nginx/proxy.conf
COPY --chmod=0555 deploy/frontend/start.sh /start-web.sh
USER 101:101
EXPOSE 8080
ENTRYPOINT ["/start-web.sh"]
