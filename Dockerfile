FROM eclipse-temurin:17-jre-alpine
LABEL maintainer="NovelForge"
LABEL description="NovelForge Studio - AI-powered novel writing workstation"

WORKDIR /app

# Copy the shaded jar
COPY packages/novelforge-studio/target/novelforge-studio-1.0.0.jar /app/novelforge-studio.jar

# Create books directory
RUN mkdir -p /root/NovelForge/books

# Expose default port
EXPOSE 8964

# Health check
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8964/api/version || exit 1

# Run with non-interactive mode
ENTRYPOINT ["java", "-jar", "/app/novelforge-studio.jar"]
CMD ["--no-auth", "--port", "8964"]
