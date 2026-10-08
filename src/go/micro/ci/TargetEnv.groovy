package go.micro.ci

class TargetEnv {
  static Map resolve(String requested, boolean servicesJob, boolean onMain, String userId, String adminId, String branchName) {
    def notes = []
    def targetEnv = (requested ?: 'dev').toString().trim().toLowerCase()
    if (!(targetEnv in ['dev', 'prod'])) {
      targetEnv = 'dev'
    }
    if (targetEnv == 'prod' && servicesJob) {
      notes << 'TARGET_ENV=prod ignored on services/* — promote on release/<service> (DevOps).'
      targetEnv = 'dev'
    }
    if (targetEnv == 'prod' && !onMain) {
      notes << "TARGET_ENV=prod ignored on branch ${branchName} — GitOps bump stays off."
      targetEnv = 'dev'
    }
    if (targetEnv == 'prod') {
      if (!userId) {
        notes << 'TARGET_ENV=prod ignored on webhook/SCM — dev merge only writes env/dev/<service>.yaml.'
        targetEnv = 'dev'
      } else if (userId != adminId) {
        return [
          error: "prod is DevOps only (${adminId}). Developer ${userId} stops at env/dev/<service>.yaml.",
          notes: notes,
        ]
      }
    }
    return [env: targetEnv, notes: notes]
  }
}
