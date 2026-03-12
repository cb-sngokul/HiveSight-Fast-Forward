# HiveSight — Temporal Billing Validation Engine

> "Because Hindsight is too expensive for Enterprise Data."

Simulate the next **18 months** of your Chargebee subscription lifecycle. Catch "time-bomb" errors before they occur—particularly around **scheduled changes (Ramps)** that the standard Chargebee UI ignores.

## Tech Stack

- **Backend:** Java 17 + Spring Boot
- **Frontend:** HTML, CSS, Bootstrap 5
- **API:** REST (Chargebee REST API via RestTemplate)

---

## Steps to Run on a New Machine (For Teammates)

### Step 1: Prerequisites

- **Java 17 or 21** — Check with `java -version`
  - Install: [Adoptium](https://adoptium.net/) or [Amazon Corretto](https://aws.amazon.com/corretto/)
- **No Maven required** — The project includes Maven Wrapper (`mvnw`)

### Step 2: Get the Project

```bash
# Option A: Clone from Git
git clone <repository-url>
cd Hivesight

# Option B: Copy the project folder (e.g. via ZIP, USB, shared drive)
cd Hivesight
```

### Step 3: Configure Chargebee (Optional for demo)

Create `src/main/resources/application.properties` or set environment variables:

**Option A — Environment variables:**
```bash
export CHARGEBEE_SITE=your-site-test
export CHARGEBEE_API_KEY=test_xxxxxxxx
```

**Option B — Edit `src/main/resources/application.properties`:**
```properties
chargebee.site=your-site-test
chargebee.api-key=test_xxxxxxxx
chargebee.timezone=Asia/Kolkata
```

> Get these from: Chargebee Dashboard → Settings → Configure Chargebee → API Keys  
> Use a **test/sandbox** site for development.  
> Set `chargebee.timezone` to match your Chargebee site (Settings → Business Profile → Time zone).

### Step 4: Run the Application

```bash
# Make mvnw executable (first time only, on Linux/macOS)
chmod +x mvnw

# Run the app (Maven will download dependencies on first run)
./mvnw spring-boot:run
```

**Windows:**
```cmd
mvnw.cmd spring-boot:run
```

### Step 5: Open in Browser

- **URL:** http://localhost:8765
- **Health check:** http://localhost:8765/api/health

### Step 6: Use the UI

1. **Fetch List** — Load subscriptions (with or without scheduled changes)
2. **Select** a subscription by clicking it
3. **Simulate 18 Months** — Run the temporal simulation
4. **Validate Ghost of March** — Check if subscription terminates on expected date

---

## Alternative: Build JAR and Run

```bash
# Build
./mvnw clean package -DskipTests

# Run the JAR
java -jar target/hivesight-1.0.0.jar
```

---

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/health` | Health check |
| GET | `/api/subscriptions` | List subscriptions |
| GET | `/api/simulate/{id}?months=18` | Simulate lifecycle |
| GET | `/api/validate/ghost-of-march/{id}?expected_cancel=2027-11-30` | Validate |

---

## Case Study: Ghost of March 31

- **Current:** Quarterly billing (UI shows next renewal March 31, 2028)
- **Ramp:** Oct 1 → shift to Monthly
- **Cancel:** Nov 30, 2027
- **HiveSight:** Correctly simulates termination on Nov 30 ✓

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `java: command not found` | Install JDK 17+ and ensure `JAVA_HOME` is set |
| `Permission denied: ./mvnw` | Run `chmod +x mvnw` |
| Port 8765 in use | Change port in `application.properties`: `server.port=8081` |
| Chargebee API errors | Verify `chargebee.site` and `chargebee.api-key` in config |
| No subscriptions listed | Ensure Chargebee site has subscriptions; try without `has_scheduled_changes` filter |

---

## Prerequisites Summary

- Java 17 or 21
- Chargebee account (optional; [Subscription Ramps](https://app.chargebee.com/request_access/subscription-ramps) for full features)
