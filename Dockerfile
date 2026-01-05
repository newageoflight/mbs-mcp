# Build stage
FROM node:20-alpine AS builder

WORKDIR /app

# Copy package files
COPY package*.json ./

# Install all dependencies (including devDependencies for build)
RUN npm ci

# Copy source code and data
COPY tsconfig.json ./
COPY src/ ./src/
COPY mbs-xml-20260101.xml ./

# Build TypeScript
RUN npm run build

# Production stage
FROM node:20-alpine AS production

WORKDIR /app

# Copy package files
COPY package*.json ./

# Install only production dependencies
RUN npm ci --only=production && npm cache clean --force

# Copy built files from builder
COPY --from=builder /app/dist ./dist

# Copy MBS data file
COPY mbs-xml-20260101.xml ./

# Cloud Run uses PORT environment variable
ENV PORT=8080

# Expose port for Cloud Run
EXPOSE 8080

# Run the HTTP server
CMD ["node", "dist/http-server.js"]
