package app.s3vault.data.remote

/**
 * Genera las políticas de ejemplo para el bucket y prefijo configurados. La política de
 * identidad (IAM) es el límite de seguridad real; la app solo la sugiere, nunca la crea.
 * Sin permisos de borrado ni `s3:*`.
 */
object IamPolicies {

    /** Política de identidad mínima para un usuario/rol IAM dedicado. */
    fun identityPolicy(bucket: String, prefix: String): String {
        val listCondition = if (prefix.isEmpty()) "" else """,
      "Condition": { "StringLike": { "s3:prefix": ["${prefix}*"] } }"""
        return """{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ListConfiguredPrefixOnly",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::$bucket"$listCondition
    },
    {
      "Sid": "ReadWriteObjectsUnderPrefix",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::$bucket/${prefix}*"
    }
  ]
}"""
    }

    /** Política de bucket opcional: exige HTTPS. No concede acceso a nadie. */
    fun bucketPolicyRequireTls(bucket: String): String = """{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DenyInsecureTransport",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:*",
      "Resource": ["arn:aws:s3:::$bucket", "arn:aws:s3:::$bucket/*"],
      "Condition": { "Bool": { "aws:SecureTransport": "false" } }
    }
  ]
}"""
}
