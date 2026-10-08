package go.micro.ci

class ServiceCatalog {
  static String canonical(String service) {
    return service == 'noti' ? 'notification' : service
  }

  static List<String> names() {
    return specs().keySet().toList().sort()
  }

  static Map spec(String service) {
    def found = specs()[service]
    if (!found) {
      return null
    }
    def out = new LinkedHashMap(found)
    out.gitopsRepo = 'https://github.com/minhtri1612/go-micro-gitops.git'
    out.gitBranch = 'main'
    return out
  }

  static boolean servicesJob(String service, String jobName) {
    def want = canonical(service)
    def job = jobName ?: ''
    return job == "services/${want}" || job.startsWith("services/${want}/")
  }

  static boolean releaseJob(String service, String jobName) {
    def want = canonical(service)
    def job = jobName ?: ''
    return job == "release/${want}" || job.startsWith("release/${want}/")
  }

  static boolean gitopsBumpBranch(String changeId, String jobName, String branchName) {
    if (changeId?.trim()) {
      return false
    }
    if ((jobName ?: '').startsWith('release/')) {
      return true
    }
    def b = (branchName ?: '').trim()
    if (!b) {
      return true
    }
    return b == 'main' || b == 'origin/main' || b.endsWith('/main')
  }

  static String ownershipError(String service, String jobName, String expectedRaw) {
    def want = canonical(service)
    def job = jobName ?: ''
    def expected = expectedRaw?.toString()?.trim()
    if (expected && canonical(expected) != want) {
      return "ciGoMicroService: EXPECTED_SERVICE='${expected}' != Jenkinsfile service='${service}'"
    }
    if (releaseJob(service, job)) {
      if (!expected) {
        return "ciGoMicroService: release job '${job}' must set EXPECTED_SERVICE=${want} (Job DSL)"
      }
      return null
    }
    if (servicesJob(service, job)) {
      return null
    }
    return "ciGoMicroService: service='${service}' is not allowed on job '${job}'. Need services/${want}/... or release/${want}"
  }

  private static Map specs() {
    return [
      product     : [imageRepo: 'minhtri1612/product-service',      envKey: 'product',   kind: 'go',   gitRepo: 'https://github.com/minhtri1612/go-micro-product.git'],
      inventory   : [imageRepo: 'minhtri1612/inventory-service',    envKey: 'inventory', kind: 'go',   gitRepo: 'https://github.com/minhtri1612/go-micro-inventory.git'],
      order       : [imageRepo: 'minhtri1612/order-service',        envKey: 'order',     kind: 'go',   gitRepo: 'https://github.com/minhtri1612/go-micro-order.git'],
      payment     : [imageRepo: 'minhtri1612/payment-service',      envKey: 'payment',   kind: 'go',   gitRepo: 'https://github.com/minhtri1612/go-micro-payment.git'],
      notification: [imageRepo: 'minhtri1612/notification-service', envKey: 'noti',      kind: 'go',   gitRepo: 'https://github.com/minhtri1612/go-micro-notification.git'],
      noti        : [imageRepo: 'minhtri1612/notification-service', envKey: 'noti',      kind: 'go',   gitRepo: 'https://github.com/minhtri1612/go-micro-notification.git'],
      client      : [imageRepo: 'minhtri1612/client',               envKey: 'client',    kind: 'node', gitRepo: 'https://github.com/minhtri1612/go-micro-client.git'],
    ]
  }
}
